// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.actions;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Container for the data one needs to resolve aggregate artifacts from events signaling the
 * completion of a target or an aspect ({@code TargetCompleteEvent} and {@code
 * AspectCompleteEvent}).
 *
 * <p>This is needed because some artifacts (tree artifacts and Filesets) are in fact aggregations
 * of multiple files.
 */
public final class CompletionContext {
  public static final CompletionContext FAILED_COMPLETION_CTX =
      new CompletionContext(
          ImmutableMap.of(),
          ImmutableMap.of(),
          ArtifactPathResolver.IDENTITY,
          new ActionInputMap(0),
          false);

  private final ArtifactPathResolver pathResolver;
  private final Map<Artifact, TreeArtifactValue> treeArtifacts;
  private final Map<Artifact, FilesetOutputTree> filesets;
  // Only contains the metadata for 'important' artifacts of the Target/Aspect that completed. Any
  // 'unimportant' artifacts produced by internal output groups (most importantly, _validation) will
  // not be included to avoid retaining many GB on the heap. This ActionInputMap must only be
  // consulted with respect to known-important artifacts (e.g. artifacts referenced in BEP).
  private final ActionInputMap importantInputMap;
  private final boolean expandFilesets;

  @VisibleForTesting
  public CompletionContext(
      Map<Artifact, TreeArtifactValue> treeArtifacts,
      Map<Artifact, FilesetOutputTree> filesets,
      ArtifactPathResolver pathResolver,
      ActionInputMap importantInputMap,
      boolean expandFilesets) {
    this.treeArtifacts = treeArtifacts;
    this.filesets = filesets;
    this.pathResolver = pathResolver;
    this.importantInputMap = importantInputMap;
    this.expandFilesets = expandFilesets;
  }

  public static CompletionContext create(
      Map<Artifact, TreeArtifactValue> treeArtifacts,
      Map<Artifact, FilesetOutputTree> filesets,
      boolean expandFilesets,
      ActionInputMap inputMap,
      ActionInputMap importantInputMap,
      PathResolverFactory pathResolverFactory) {
    ArtifactPathResolver pathResolver =
        pathResolverFactory.shouldCreatePathResolverForArtifactValues()
            ? pathResolverFactory.createPathResolverForArtifactValues(
                inputMap,
                Maps.transformValues(treeArtifacts, TreeArtifactValue::getChildren),
                filesets)
            : ArtifactPathResolver.IDENTITY;
    return new CompletionContext(
        treeArtifacts,
        filesets,
        pathResolver,
        importantInputMap,
        expandFilesets);
  }

  public ArtifactPathResolver pathResolver() {
    return pathResolver;
  }

  public ActionInputMap getImportantInputMap() {
    return importantInputMap;
  }

  @Nullable
  public FileArtifactValue getFileArtifactValue(Artifact artifact) {
    return importantInputMap.getInputMetadata(artifact);
  }

  /** Visits the expansion of the given artifacts. */
  public void visitArtifacts(Iterable<Artifact> artifacts, ArtifactReceiver receiver) {
    for (Artifact artifact : artifacts) {
      if (artifact.isRunfilesTree()) {
        continue;
      }
      if (artifact.isFileset()) {
        if (expandFilesets) {
          visitFileset(artifact, receiver);
        }
      } else if (artifact.isTreeArtifact()) {
        FileArtifactValue treeArtifactMetadata = importantInputMap.getInputMetadata(artifact);
        if (treeArtifactMetadata == null) {
          BugReport.sendBugReport(
              new IllegalStateException(
                  String.format(
                      "missing artifact metadata for tree artifact: %s",
                      artifact.toDebugString())));
        }

        TreeArtifactValue treeValue =
            checkNotNull(
                treeArtifacts.get(artifact), "Missing expansion for tree artifact: %s", artifact);
        for (Artifact child : treeValue.getChildren()) {
          receiver.accept(child);
        }
      } else {
        receiver.accept(artifact);
      }
    }
  }

  private void visitFileset(Artifact filesetArtifact, ArtifactReceiver receiver) {
    FilesetOutputTree filesetOutput =
        checkNotNull(
            filesets.get(filesetArtifact),
            "Missing %s, available filesets: %s",
            filesetArtifact,
            filesets.keySet());
    for (FilesetOutputSymlink link : filesetOutput.symlinks()) {
      receiver.acceptFilesetMapping(
          filesetArtifact, link.name(), link.target().getPath(), link.metadata());
    }
  }

  /** A function that accepts an {@link Artifact}. */
  public interface ArtifactReceiver {
    void accept(Artifact artifact);

    void acceptFilesetMapping(
        Artifact fileset, PathFragment relName, Path targetFile, FileArtifactValue metadata);
  }

  /** A factory for {@link ArtifactPathResolver}. */
  public interface PathResolverFactory {
    ArtifactPathResolver createPathResolverForArtifactValues(
        ActionInputMap actionInputMap,
        Map<Artifact, ImmutableSortedSet<TreeFileArtifact>> treeArtifacts,
        Map<Artifact, FilesetOutputTree> filesets);

    boolean shouldCreatePathResolverForArtifactValues();
  }
}

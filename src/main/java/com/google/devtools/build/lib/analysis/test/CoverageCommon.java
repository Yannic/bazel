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

package com.google.devtools.build.lib.analysis.test;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.starlarkbuildapi.test.CoverageCommonApi;
import com.google.devtools.build.lib.starlarkbuildapi.test.InstrumentedFilesInfoApi;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

/** Helper functions for Starlark to access coverage-related infrastructure. */
public class CoverageCommon implements CoverageCommonApi<ConstraintValueInfo, StarlarkRuleContext> {

  @Override
  public InstrumentedFilesInfoApi instrumentedFilesInfo(
      StarlarkRuleContext starlarkRuleContext,
      Sequence<?> sourceAttributes, // <String>
      Sequence<?> dependencyAttributes, // <String>
      Object
          supportFiles, // Depset<Artifact>|Sequence<Artifact|Depset<Artifact>|FilesToRunProvider>
      Dict<?, ?> environment, // <String, String>
      Object extensions,
      Sequence<?> metadataFiles, // Sequence<Artifact>
      Object reportedToActualSourcesObject,
      StarlarkThread thread)
      throws EvalException {
    List<String> extensionsList =
        extensions == Starlark.NONE ? null : Sequence.cast(extensions, String.class, "extensions");
    NestedSet<Tuple> reportedToActualSources =
        reportedToActualSourcesObject == Starlark.NONE
            ? NestedSetBuilder.create(Order.STABLE_ORDER)
            : Depset.cast(reportedToActualSourcesObject, Tuple.class, "reported_to_actual_sources");
    Dict<String, String> environmentDict =
        Dict.cast(environment, String.class, String.class, "coverage_environment");
    NestedSetBuilder<Artifact> supportFilesBuilder = NestedSetBuilder.stableOrder();
    if (supportFiles instanceof Depset) {
      supportFilesBuilder.addTransitive(
          Depset.cast(supportFiles, Artifact.class, "coverage_support_files"));
    } else if (supportFiles instanceof Sequence<?> supportFilesSequence) {
      for (int i = 0; i < supportFilesSequence.size(); i++) {
        Object supportFilesElement = supportFilesSequence.get(i);
        if (supportFilesElement instanceof Depset) {
          supportFilesBuilder.addTransitive(
              Depset.cast(supportFilesElement, Artifact.class, "coverage_support_files"));
        } else if (supportFilesElement instanceof Artifact artifact) {
          supportFilesBuilder.add(artifact);
        } else {
          throw Starlark.errorf(
              "at index %d of coverage_support_files, got element of type %s, want one of depset,"
                  + " File or FilesToRunProvider",
              i, Starlark.type(supportFilesElement));
        }
      }
    } else {
      // Should have been verified by Starlark before this function is called
      throw new IllegalStateException();
    }
    if (!supportFilesBuilder.isEmpty()
        || !reportedToActualSources.isEmpty()
        || !environmentDict.isEmpty()) {
      BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread);
    }
    return createInstrumentedFilesInfo(
        starlarkRuleContext.getRuleContext(),
        Sequence.cast(sourceAttributes, String.class, "source_attributes"),
        Sequence.cast(dependencyAttributes, String.class, "dependency_attributes"),
        supportFilesBuilder.build(),
        ImmutableMap.copyOf(environmentDict),
        extensionsList,
        Sequence.cast(metadataFiles, Artifact.class, "metadata_files"),
        reportedToActualSources);
  }

  private static InstrumentedFilesInfo createInstrumentedFilesInfo(
      RuleContext ruleContext,
      List<String> sourceAttributes,
      List<String> dependencyAttributes,
      NestedSet<Artifact> supportFiles,
      ImmutableMap<String, String> environment,
      @Nullable List<String> extensions,
      @Nullable List<Artifact> metadataFiles,
      NestedSet<Tuple> reportedToActualSources) {
    FileTypeSet fileTypeSet = FileTypeSet.ANY_FILE;
    if (extensions != null) {
      if (extensions.isEmpty()) {
        fileTypeSet = FileTypeSet.NO_FILE;
      } else {
        FileType[] fileTypes = new FileType[extensions.size()];
        Arrays.setAll(fileTypes, i -> FileType.of(extensions.get(i)));
        fileTypeSet = FileTypeSet.of(fileTypes);
      }
    }
    InstrumentationSpec instrumentationSpec =
        new InstrumentationSpec(fileTypeSet)
            .withSourceAttributes(sourceAttributes)
            .withDependencyAttributes(dependencyAttributes);
    return InstrumentedFilesCollector.collect(
        ruleContext,
        instrumentationSpec,
        /* coverageSupportFiles= */ supportFiles,
        /* coverageEnvironment= */ environment,
        /* reportedToActualSources= */ reportedToActualSources,
        /* additionalMetadata= */ metadataFiles);
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<coverage_common>");
  }
}

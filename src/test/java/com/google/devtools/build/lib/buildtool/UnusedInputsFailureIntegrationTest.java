// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.TargetCompleteEvent;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions.OutputGroupFileModes;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.skyframe.DetailedException;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.Keep;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for Starlark "unused inputs list" functionality on failures caused by unused inputs. */
@RunWith(TestParameterInjector.class)
public final class UnusedInputsFailureIntegrationTest extends BuildIntegrationTestCase {

  @TestParameter private boolean keepGoing;

  @Before
  public void setOptions() {
    addOptions("--keep_going=" + keepGoing);
  }

  private List<TargetCompleteEvent> listenForTargetCompleteEvents() {
    List<TargetCompleteEvent> events = new ArrayList<>();
    runtimeWrapper.registerSubscriber(
        new Object() {
          @Subscribe
          @Keep
          private void targetComplete(TargetCompleteEvent event) {
            events.add(event);
          }
        });
    return events;
  }

  @Test
  public void incrementalFailureOnUnusedInput() throws Exception {
    RecordingBugReporter bugReporter = recordBugReportsAndReinitialize();
    write(
        "foo/pruning.bzl",
        """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_file = ctx.actions.declare_file(ctx.label.name + ".unused")
            ctx.actions.run(
                # Make sure original inputs are one level down,
                # so 'leaf unrolling' doesn't get them
                inputs = depset(transitive = [ctx.attr.filler.files, inputs]),
                outputs = [output, unused_file],
                arguments = [output.path, unused_file.path] + [f.path for f in inputs.to_list()],
                executable = ctx.executable.executable,
                unused_inputs_list = unused_file,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "filler": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        """);
    write("foo/unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > $unused")
        .setExecutable(true);
    write("foo/gen_run.sh", "true").setExecutable(true);
    write("foo/filler");
    write(
        "foo/BUILD",
        """
        load("//foo:pruning.bzl", "build_rule")

        build_rule(
            name = "foo",
            executable = ":unused.sh",
            filler = ":filler",
            inputs = ":in",
        )

        genrule(
            name = "gen",
            outs = ["in"],
            cmd = "$(location :gen_run.sh) && touch $@",
            tools = [":gen_run.sh"],
        )
        """);

    buildTarget("//foo:foo");
    bugReporter.assertNoExceptions();

    write("foo/gen_run.sh", "false");

    List<TargetCompleteEvent> targetCompleteEvents = listenForTargetCompleteEvents();
    if (keepGoing) {
      buildTarget("//foo:foo");
      bugReporter.assertNoExceptions();

      TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
      assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/foo.out");
      assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty();
    } else {
      RecordingOutErr outErr = new RecordingOutErr();
      this.outErr = outErr;
      BuildFailedException e = assertThrows(BuildFailedException.class, () -> buildTarget("//foo"));
      assertThat(e.getDetailedExitCode().getFailureDetail())
          .comparingExpectedFieldsOnly()
          .isEqualTo(
              FailureDetails.FailureDetail.newBuilder()
                  .setExecution(
                      FailureDetails.Execution.newBuilder()
                          .setCode(FailureDetails.Execution.Code.UNEXPECTED_EXCEPTION)
                          .build())
                  .build());
      assertThat(outErr.errAsLatin1()).contains("Executing genrule //foo:gen failed");
      Throwable cause = bugReporter.getFirstCause();
      assertThat(cause).hasMessageThat().contains("Error evaluating artifact nested set");
      assertThat(cause).hasMessageThat().contains("foo/gen_run.sh");

      // TODO: b/414856090 - There should be a failed TargetCompleteEvent posted.
      assertThat(targetCompleteEvents).isEmpty();
    }
  }

  /**
   * Regression test for b/218911068.
   *
   * <p>Doesn't reproduce the exact crash since that requires BEP infrastructure to be set up, but
   * asserts that the {@link TargetCompleteEvent} does not report the fileset artifact in the broken
   * build.
   */
  @Test
  public void incrementalFailureOnUnusedInput_topLevelFileset() throws Exception {
    assume().that(AnalysisMock.get().isThisBazel()).isFalse(); // No Filesets in bazel.
    write(
        "foo/pruning.bzl",
        """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_file = ctx.actions.declare_file(ctx.label.name + ".unused")
            ctx.actions.run(
                # Make sure original inputs are one level down,
                # so 'leaf unrolling' doesn't get them
                inputs = depset(transitive = [ctx.attr.filler.files, inputs]),
                outputs = [output, unused_file],
                arguments = [output.path, unused_file.path] + [f.path for f in inputs.to_list()],
                executable = ctx.executable.executable,
                unused_inputs_list = unused_file,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "filler": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        """);
    write("foo/unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > $unused")
        .setExecutable(true);
    write("foo/gen_run.sh", "true").setExecutable(true);
    write("foo/filler");
    write(
        "foo/BUILD",
        """
        load("//foo:pruning.bzl", "build_rule")

        Fileset(name = "fs", entries = [FilesetEntry(files = [":foo"])])

        build_rule(
            name = "foo",
            executable = ":unused.sh",
            filler = ":filler",
            inputs = ":in",
        )

        genrule(
            name = "gen",
            outs = ["in"],
            cmd = "$(location :gen_run.sh) && touch $@",
            tools = [":gen_run.sh"],
        )
        """);

    buildTarget("//foo:fs");

    write("foo/gen_run.sh", "false");

    List<TargetCompleteEvent> targetCompleteEvents = listenForTargetCompleteEvents();
    if (keepGoing) {
      buildTarget("//foo:fs");

      TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
      assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/fs");
      assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty();
    } else {
      assertThrows(BuildFailedException.class, () -> buildTarget("//foo:fs"));
      assertContainsError("Executing genrule //foo:gen failed");

      TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
      assertThat(getAllReportedArtifacts(targetCompleteEvent)).isEmpty();
      assertThat(getRootCauseLabels(targetCompleteEvent)).containsExactly("//foo:gen");
    }
  }

  /**
   * Regression test for b/185998331.
   *
   * <p>The action graph is:
   *
   * <pre>
   *            top [consume.out] -> [top.out]
   *                           |
   *           consume [consume.sh, prune.out] -> [consume.out]
   *                           |
   *      prune [prune.sh, [bad.out, good.out]] -> [prune.out, unused_list]
   *                   /                \
   *     bad [bad.sh] -> [bad.out]     good [] -> [good.out]
   * </pre>
   *
   * where 'prune' reports 'bad' as an unused input. On the first build, 'consume' fails. On the
   * second build, 'bad' fails. If the error is not handled correctly by 'prune', 'top' won't know
   * that 'consume' is unavailable.
   */
  @Test
  public void incrementalFailureOnUnusedInput_downstreamInputNotReady() throws Exception {
    write(
        "foo/defs.bzl",
        """
        def _example_rule_impl(ctx):
            bad = ctx.actions.declare_file("bad.out")
            ctx.actions.run(
                outputs = [bad],
                executable = ctx.executable.bad_sh,
                arguments = [bad.path],
            )

            good = ctx.actions.declare_file("good.out")
            ctx.actions.run_shell(outputs = [good], command = "touch %s" % good.path)

            unused_list = ctx.actions.declare_file("unused_list")
            prune = ctx.actions.declare_file("prune.out")
            ctx.actions.run(
                outputs = [prune, unused_list],
                inputs = [bad, good],
                unused_inputs_list = unused_list,
                executable = ctx.executable.prune_sh,
                arguments = [prune.path, unused_list.path, bad.path],
            )

            consume = ctx.actions.declare_file("consume.out")
            ctx.actions.run(
                outputs = [consume],
                inputs = [prune],
                executable = ctx.executable.consume_sh,
                arguments = [consume.path],
            )

            top = ctx.actions.declare_file("top.out")
            ctx.actions.run_shell(
                outputs = [top],
                inputs = [consume],
                command = "touch %s" % top.path,
            )
            return DefaultInfo(files = depset([top]))

        example_rule = rule(
            implementation = _example_rule_impl,
            attrs = {
                "bad_sh": attr.label(
                    executable = True,
                    allow_single_file = True,
                    cfg = "exec",
                    default = "bad.sh",
                ),
                "prune_sh": attr.label(
                    executable = True,
                    allow_single_file = True,
                    cfg = "exec",
                    default = "prune.sh",
                ),
                "consume_sh": attr.label(
                    executable = True,
                    allow_single_file = True,
                    cfg = "exec",
                    default = "consume.sh",
                ),
            },
        )
        """);
    write(
        "foo/BUILD",
        """
        load(":defs.bzl", "example_rule")

        example_rule(name = "example")
        """);
    write("foo/bad.sh", "#!/bin/bash", "touch $1").setExecutable(true);
    write("foo/prune.sh", "#!/bin/bash", "touch $1 && echo $3 > $2").setExecutable(true);
    write("foo/consume.sh", "#!/bin/bash", "exit 1").setExecutable(true);

    assertThrows(BuildFailedException.class, () -> buildTarget("//foo:example"));
    assertContainsError("Action foo/consume.out failed");

    write("foo/bad.sh", "#!/bin/bash", "exit 1").setExecutable(true);
    write("foo/consume.sh", "#!/bin/bash", "touch $@").setExecutable(true);

    List<TargetCompleteEvent> targetCompleteEvents = listenForTargetCompleteEvents();
    if (keepGoing) {
      buildTarget("//foo:example");

      TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
      assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/top.out");
      assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty();
    } else {
      assertThrows(BuildFailedException.class, () -> buildTarget("//foo:example"));
      assertContainsError("Action foo/bad.out failed");

      TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
      assertThat(getAllReportedArtifacts(targetCompleteEvent)).isEmpty();
      assertThat(getRootCauseLabels(targetCompleteEvent)).containsExactly("//foo:example");
    }
  }

  @Test
  public void incrementalUnusedSymlinkCycle() throws Exception {
    RecordingBugReporter bugReporter = recordBugReportsAndReinitialize();
    write(
        "foo/pruning.bzl",
        """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_inputs_list = ctx.actions.declare_file(ctx.label.name + ".unused")
            arguments = [output.path, unused_inputs_list.path]
            for input in inputs.to_list():
                arguments += [input.path]
            ctx.actions.run(
                inputs = inputs,
                outputs = [output, unused_inputs_list],
                arguments = arguments,
                executable = ctx.executable.executable,
                unused_inputs_list = unused_inputs_list,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        """);
    Path unusedSh =
        write("foo/all_unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > $unused");
    unusedSh.setExecutable(true);
    Path inPath = write("foo/in");
    write(
        "foo/BUILD",
        """
        load("//foo:pruning.bzl", "build_rule")

        build_rule(
            name = "prune",
            executable = ":all_unused.sh",
            inputs = ":in",
        )
        """);

    buildTarget("//foo:prune");
    bugReporter.assertNoExceptions();

    inPath.delete();
    inPath.createSymbolicLink(PathFragment.create("in"));

    List<TargetCompleteEvent> targetCompleteEvents = listenForTargetCompleteEvents();
    if (keepGoing) {
      buildTarget("//foo:prune");
      bugReporter.assertNoExceptions();

      TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
      assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/prune.out");
      assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty();
    } else {
      RecordingOutErr outErr = new RecordingOutErr();
      this.outErr = outErr;
      BuildFailedException e =
          assertThrows(BuildFailedException.class, () -> buildTarget("//foo:prune"));
      assertDetailedExitCodeIsSourceIOFailure(e);
      Throwable cause = bugReporter.getFirstCause();
      assertDetailedExitCodeIsSourceIOFailure(cause);
      assertThat(cause).hasMessageThat().isEqualTo("error reading file '//foo:in': Symlink cycle");
      assertThat(outErr.errAsLatin1()).contains("error reading file '//foo:in': Symlink cycle");

      TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
      assertThat(getAllReportedArtifacts(targetCompleteEvent)).isEmpty();
      assertThat(getRootCauseLabels(targetCompleteEvent)).containsExactly("//foo:prune");
    }
  }

  private static final FailureDetails.FailureDetail SOURCE_IO_FAILURE =
      FailureDetails.FailureDetail.newBuilder()
          .setExecution(
              FailureDetails.Execution.newBuilder()
                  .setCode(FailureDetails.Execution.Code.SOURCE_INPUT_IO_EXCEPTION))
          .build();

  private static void assertDetailedExitCodeIsSourceIOFailure(Throwable exception) {
    assertThat(exception).isInstanceOf(DetailedException.class);
    assertThat(((DetailedException) exception).getDetailedExitCode().getFailureDetail())
        .comparingExpectedFieldsOnly()
        .isEqualTo(SOURCE_IO_FAILURE);
  }

  @Test
  public void incrementalUnusedDanglingSymlink() throws Exception {
    write(
        "foo/pruning.bzl",
        """
        def _impl(ctx):
            inputs = ctx.attr.inputs.files
            output = ctx.actions.declare_file(ctx.label.name + ".out")
            unused_inputs_list = ctx.actions.declare_file(ctx.label.name + ".unused")
            arguments = [output.path, unused_inputs_list.path]
            for input in inputs.to_list():
                arguments += [input.path]
            ctx.actions.run(
                inputs = inputs,
                outputs = [output, unused_inputs_list],
                arguments = arguments,
                executable = ctx.executable.executable,
                unused_inputs_list = unused_inputs_list,
            )
            return DefaultInfo(files = depset([output]))

        build_rule = rule(
            attrs = {
                "inputs": attr.label(allow_files = True),
                "executable": attr.label(executable = True, allow_files = True, cfg = "exec"),
            },
            implementation = _impl,
        )
        """);
    Path unusedSh =
        write("foo/all_unused.sh", "touch $1", "shift", "unused=$1", "shift", "echo $@ > $unused");
    unusedSh.setExecutable(true);
    Path inPath = write("foo/in");
    write(
        "foo/BUILD",
        """
        load("//foo:pruning.bzl", "build_rule")

        build_rule(
            name = "prune",
            executable = ":all_unused.sh",
            inputs = ":in",
        )
        """);

    buildTarget("//foo:prune");

    inPath.delete();
    inPath.createSymbolicLink(PathFragment.create("nope"));

    List<TargetCompleteEvent> targetCompleteEvents = listenForTargetCompleteEvents();
    buildTarget("//foo:prune");

    TargetCompleteEvent targetCompleteEvent = Iterables.getOnlyElement(targetCompleteEvents);
    assertThat(getAllReportedArtifacts(targetCompleteEvent)).containsExactly("foo/prune.out");
    assertThat(getRootCauseLabels(targetCompleteEvent)).isEmpty();
  }

  private static ImmutableSet<String> getAllReportedArtifacts(TargetCompleteEvent event) {
    return event.reportedArtifacts(OutputGroupFileModes.DEFAULT).artifacts.stream()
        .flatMap(set -> set.toList().stream())
        .map(artifact -> artifact.getRootRelativePath().getPathString())
        .collect(toImmutableSet());
  }

  private static ImmutableSet<String> getRootCauseLabels(TargetCompleteEvent event) {
    return event.getRootCauses().toList().stream()
        .map(cause -> cause.getLabel().toString())
        .collect(toImmutableSet());
  }
}

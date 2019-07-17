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

package com.google.devtools.build.skydoc.fakebuildapi.proto;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.FileApi;
import com.google.devtools.build.lib.skylarkbuildapi.ProtoInfoApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;

/** Fake implementation of {@link ProtoInfoApi.Provider}. */
public class FakeProtoInfoApiProvider implements ProtoInfoApi.Provider<FileApi> {

  @Override
  public void repr(SkylarkPrinter printer) {}

  @Override
  public ProtoInfoApi<?> constructor(
      SkylarkNestedSet transitiveImports,
      SkylarkNestedSet transitiveSources,
      SkylarkList directSources,
      SkylarkNestedSet checkDepsSources,
      FileApi directDescriptorSet,
      Location loc)
      throws EvalException {
    return null;
  }
}

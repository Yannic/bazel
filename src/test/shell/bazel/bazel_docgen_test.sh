#!/usr/bin/env bash
#
# Copyright 2020 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# A smoke file test for generated doc files.

set -euo pipefail

# --- begin runfiles.bash initialization v2 ---
# Copy-pasted from the Bazel Bash runfiles library v2.
set -uo pipefail; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
    source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
    source "$0.runfiles/$f" 2>/dev/null || \
    source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
    source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
    { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v2 ---

EXPECTED_ZIPS="build-encyclopedia.zip skylark-library.zip"

for out in $EXPECTED_ZIPS; do
  filepath="$(rlocation io_bazel/src/main/java/com/google/devtools/build/lib/$out)"
  # Test that the file has a corrected ZIP structure.
  zipinfo "$filepath" 1>/dev/null
done

COMMAND_LINE_REF_FILE="$(rlocation io_bazel/src/main/java/com/google/devtools/build/lib/command-line-reference.html)"
# Test that the file contains (at least) the title string.
grep -q "Command-Line Reference" "$COMMAND_LINE_REF_FILE"

#!/bin/sh
#
# Copyright 2016 Google Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

ROOT="$(cd $(dirname "$0")/..; pwd)"

PROPERTIES="$@"

SOURCE_PATH_PROPERTY_KEY="--jvm_flag=-Dcom.google.gitiles.sourcePath"
if [ -z "$PROPERTIES" ] || [ $PROPERTIES != *$SOURCE_PATH_PROPERTY_KEY* ]; then
  PROPERTIES="$SOURCE_PATH_PROPERTY_KEY=$ROOT $PROPERTIES"
fi

(
  cd "$ROOT"
  bazel build java/com/google/gitiles/dev
)

"$ROOT/bazel-bin/java/com/google/gitiles/dev/dev" $PROPERTIES

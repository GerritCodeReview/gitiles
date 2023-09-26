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
PROPERTIES=

NUMBER_OF_ARGUMENTS=$#
while test $# -gt 0
do
    case "$1" in
        --debug) PROPERTIES="$PROPERTIES --debug"
            ;;
        *) if [ $NUMBER_OF_ARGUMENTS -eq $# ]; then PROPERTIES="$PROPERTIES --jvm_flag=-Dcom.google.gitiles.configPath=$1"; fi
            ;;
    esac
    shift
done

PROPERTIES="$PROPERTIES --jvm_flag=-Dcom.google.gitiles.sourcePath=$ROOT"

(
  cd "$ROOT"
  bazel build java/com/google/gitiles/dev
)

set -x
"$ROOT/bazel-bin/java/com/google/gitiles/dev/dev" $PROPERTIES

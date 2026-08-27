// Copyright 2026 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles.blame.cache;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BlameCacheImpl.Key}. */
@RunWith(JUnit4.class)
public class BlameCacheTest {
  @Test
  public void keyEqualsAndHashCode() {
    ObjectId c1 = ObjectId.fromString("1111111111111111111111111111111111111111");
    ObjectId c2 = ObjectId.fromString("2222222222222222222222222222222222222222");
    ObjectId c3 = ObjectId.fromString("3333333333333333333333333333333333333333");

    BlameCacheImpl.Key k1 = new BlameCacheImpl.Key(c1, "foo.txt");
    BlameCacheImpl.Key k2 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of());
    BlameCacheImpl.Key k3 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c2, c3));
    BlameCacheImpl.Key k4 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c3, c2));
    BlameCacheImpl.Key k5 = new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c2));

    assertThat(k1).isEqualTo(k2);
    assertThat(k1.hashCode()).isEqualTo(k2.hashCode());

    assertThat(k3).isEqualTo(k4);
    assertThat(k3.hashCode()).isEqualTo(k4.hashCode());

    assertThat(k1).isNotEqualTo(k3);
    assertThat(k3).isNotEqualTo(k5);
  }

  @Test
  public void keyToString() {
    ObjectId c1 = ObjectId.fromString("1111111111111111111111111111111111111111");
    ObjectId c2 = ObjectId.fromString("2222222222222222222222222222222222222222");
    ObjectId c3 = ObjectId.fromString("3333333333333333333333333333333333333333");

    BlameCacheImpl.Key kNoIgnore = new BlameCacheImpl.Key(c1, "foo.txt");
    assertThat(kNoIgnore.toString()).isEqualTo("1111111111111111111111111111111111111111:foo.txt");

    BlameCacheImpl.Key kWithIgnore =
        new BlameCacheImpl.Key(c1, "foo.txt", ImmutableSet.of(c3, c2));
    assertThat(kWithIgnore.toString())
        .isEqualTo(
            "1111111111111111111111111111111111111111:foo.txt"
                + " ignore=[2222222222222222222222222222222222222222,"
                + " 3333333333333333333333333333333333333333]");
  }
}

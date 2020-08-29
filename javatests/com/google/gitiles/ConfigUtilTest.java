// Copyright 2012 Google Inc. All Rights Reserved.
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

package com.google.gitiles;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gitiles.ConfigUtil.getDuration;
import static org.junit.Assert.fail;

import java.time.Duration;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for configuration utilities. */
@RunWith(JUnit4.class)
public class ConfigUtilTest {
  @Test
  public void getDurationReturnsDuration() throws Exception {
    Duration def = Duration.ofSeconds(2);
    Config config = new Config();
    Duration t;

    config.setString("core", "dht", "timeout", "500 ms");
    t = getDuration(config, "core", "dht", "timeout", def);
    assertThat(t).isEqualTo(Duration.ofMillis(500));

    config.setString("core", "dht", "timeout", "5.2 sec");
    try {
      getDuration(config, "core", "dht", "timeout", def);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().isEqualTo("Invalid time unit value: core.dht.timeout=5.2 sec");
    }

    config.setString("core", "dht", "timeout", "1 min");
    t = getDuration(config, "core", "dht", "timeout", def);
    assertThat(t).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  public void getDurationCanReturnDefault() throws Exception {
    Duration def = Duration.ofSeconds(1);
    Config config = new Config();
    Duration t;

    t = getDuration(config, "core", null, "blank", def);
    assertThat(t).isEqualTo(Duration.ofSeconds(1));

    config.setString("core", null, "blank", "");
    t = getDuration(config, "core", null, "blank", def);
    assertThat(t).isEqualTo(Duration.ofSeconds(1));

    config.setString("core", null, "blank", " ");
    t = getDuration(config, "core", null, "blank", def);
    assertThat(t).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  public void nullAsDefault() throws Exception {
    Config config = new Config();
    Duration t;

    t = getDuration(config, "core", null, "blank", null);
    assertThat(t).isNull();

    config.setString("core", null, "blank", "");
    t = getDuration(config, "core", null, "blank", null);
    assertThat(t).isNull();

    config.setString("core", null, "blank", " ");
    t = getDuration(config, "core", null, "blank", null);
    assertThat(t).isNull();
  }
}

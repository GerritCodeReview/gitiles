// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gitiles;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.util.NB;
import org.joda.time.Duration;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs a URL using HMAC SHA-1. */
public class HmacUrlSigner implements UrlSigner {
  private static final String MAC_ALG = "HmacSHA1";
  private final SecretKeySpec signingKey;
  private final Random rng;
  private final int maxAgeSeconds;

  public HmacUrlSigner(Config cfg) throws IOException {
    String keyPath = cfg.getString("gitiles", null, "urlSignerKey");
    byte[] key;
    if (Strings.isNullOrEmpty(keyPath)) {
      key = new byte[16];
      new Random().nextBytes(key);
    } else {
      try (FileInputStream in = new FileInputStream(keyPath)) {
        key = ByteStreams.toByteArray(in);
      }
    }
    rng = new Random();
    signingKey = new SecretKeySpec(key, MAC_ALG);
    maxAgeSeconds =
        Ints.saturatedCast(
            ConfigUtil.getDuration(
                    cfg, "gitiles", null, "urlSignerMaxAge", Duration.standardSeconds(5))
                .getStandardSeconds());
  }

  @Override
  public byte[] sign(String url) {
    byte n = (byte) rng.nextInt();
    long created = System.currentTimeMillis() / 1000L;
    return encode(url, n, created);
  }

  @Override
  public boolean verify(String url, byte[] sig) {
    if (sig.length < 8) {
      return false;
    }

    byte n = sig[sig.length - 1];
    byte[] time = Arrays.copyOfRange(sig, sig.length - 9, sig.length - 1);
    for (int i = 0; i < time.length; i++) {
      time[i] ^= n;
    }
    long created = NB.decodeInt64(time, 0);
    long now = System.currentTimeMillis() / 1000L;
    if (created + maxAgeSeconds < now || created > now) {
      // Reject tokens created too long ago, or from the future.
      return false;
    }
    return Arrays.equals(sig, encode(url, n, created));
  }

  private byte[] encode(String url, byte n, long created) {
    Mac mac;
    try {
      mac = Mac.getInstance(MAC_ALG);
      mac.init(signingKey);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException("Cannot use " + MAC_ALG, e);
    }

    byte[] time = new byte[9];
    NB.encodeInt64(time, 0, created);
    for (int i = 0; i < 8; i++) {
      time[i] ^= n;
    }
    time[8] = n;
    mac.update(url.getBytes(StandardCharsets.UTF_8));
    mac.update(time);
    return Bytes.concat(mac.doFinal(), time);
  }
}

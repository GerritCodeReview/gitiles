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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Bytes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.util.NB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Signs a URL using HMAC SHA-1. */
public class HmacUrlSigner implements UrlSigner {
  private static final Logger log = LoggerFactory.getLogger(HmacUrlSigner.class);

  private static final String MAC_ALG = "HmacSHA1";
  private static byte[] processKey;

  private static synchronized byte[] getProcessKey() {
    if (processKey == null) {
      log.info("No gitiles.urlSignerKey configured; generating random key");
      byte[] key = new byte[20];
      new SecureRandom().nextBytes(key);
      processKey = key;
    }
    return processKey;
  }

  private final Random rng = new Random();
  private final SecretKeySpec signingKey;
  private final long maxAgeMillis;

  public HmacUrlSigner(Config cfg) throws IOException {
    String path = cfg.getString("gitiles", null, "urlSignerKey");
    byte[] key = Strings.isNullOrEmpty(path) ? getProcessKey() : readKey(path);
    signingKey = new SecretKeySpec(key, MAC_ALG);

    // gitiles.urlSignerMaxAge defines how long a token is valid for redirects.
    // It also specifies how long a browser may cache a token in memory, possibly
    // avoiding a redirect if the token is still valid. 5s is a reasonable lower
    // bound for the token, giving time to make the request. 1h might be a reasonable
    // upper bound, allowing some caching to reduce reauth for popular files.
    maxAgeMillis =
        ConfigUtil.getDuration(cfg, "gitiles", null, "urlSignerMaxAge", Duration.ofSeconds(5))
            .toMillis();
  }

  private static byte[] readKey(String file) throws IOException {
    Path path = FileSystems.getDefault().getPath(file);
    try {
      SetView<PosixFilePermission> bad =
          Sets.difference(
              Files.getPosixFilePermissions(path),
              ImmutableSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      if (!bad.isEmpty()) {
        path = path.toAbsolutePath();
        log.warn("Insecure permissions on {}: {}", path, Joiner.on(", ").join(bad));
      }
    } catch (UnsupportedOperationException notPosix) {
    }

    try (InputStream in = ByteStreams.limit(Files.newInputStream(path), 128)) {
      return ByteStreams.toByteArray(in);
    }
  }

  @Override
  public Signature sign(String url) {
    byte n = (byte) rng.nextInt();
    long created = System.currentTimeMillis();
    return new Signature(encode(url, n, created), created + maxAgeMillis);
  }

  @Override
  public ValidatedSignature verify(String url, byte[] sig) {
    if (sig.length < 5) {
      return null; // must have at least 5 bytes (see encode).
    }

    byte n = sig[sig.length - 1];
    long created = extractCreated(sig, n);
    long now = System.currentTimeMillis();
    long expires = created + maxAgeMillis;
    if (expires < now || created > now) {
      // Reject tokens created too long ago, or from the future.
      return null;
    }

    if (Arrays.equals(sig, encode(url, n, created))) {
      return new ValidatedSignature(expires);
    }
    return null;
  }

  private byte[] encode(String url, byte n, long createdMillis) {
    // Encode creation time as seconds since epoch, but XOR with a
    // single random byte to mix the bits around so signatures don't
    // have a predictable looking pattern. Include the random byte as
    // part of the HMAC computation, and the signature, so this can be
    // reversed safely during verify.
    byte[] time = new byte[5];
    NB.encodeInt32(time, 0, (int) MILLISECONDS.toSeconds(createdMillis));
    for (int i = 0; i < 4; i++) {
      time[i] ^= n;
    }
    time[4] = n;

    Mac mac = newMac();
    mac.update(url.getBytes(StandardCharsets.UTF_8));
    mac.update(time);
    return Bytes.concat(mac.doFinal(), time);
  }

  private static long extractCreated(byte[] sig, byte n) {
    byte[] time = Arrays.copyOfRange(sig, sig.length - 5, sig.length - 1);
    for (int i = 0; i < 4; i++) {
      time[i] ^= n;
    }
    return SECONDS.toMillis(NB.decodeUInt32(time, 0));
  }

  private Mac newMac() {
    try {
      Mac mac = Mac.getInstance(MAC_ALG);
      mac.init(signingKey);
      return mac;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException("Cannot use " + MAC_ALG, e);
    }
  }
}

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

/**
 * Signs a URL with a short term authentication token.
 *
 * @see HmacUrlSigner
 */
public interface UrlSigner {
  /**
   * Sign a URL.
   *
   * @param url URL to sign. The same string is passed to {@link #verify(String, byte[])}/
   * @return raw bytes of the authentication token. The caller will encode this in a web safe
   *     format.
   */
  public Signature sign(String url);

  /**
   * Verify a signature.
   *
   * @param url previously signed URL.
   * @param signature raw bytes of the authentication token.
   * @return validation structure if the signature is valid; {@code null} if it has expired or is
   *     invalid in any way.
   */
  public ValidatedSignature verify(String url, byte[] signature);

  /** Result of {@link UrlSigner#sign(String)}. */
  public static class Signature {
    private final byte[] signature;
    private final long expires;

    /**
     * Construct a signature.
     *
     * @param signtuare raw signature of the URL.
     * @param expires milliseconds since the epoch when {@code signature} expires; {@code 0}
     *     indicates {@code signature} expires "quickly".
     */
    public Signature(byte[] signature, long expires) {
      this.signature = signature;
      this.expires = expires;
    }

    /** @return raw bytes of the signature; callers must encode into URL. */
    public byte[] getSignature() {
      return signature;
    }

    /**
     * @return milliseconds since the epoch when this signature will expire and no longer be
     *     accepted. If 0 the signature is assumed to expire "quickly" (almost immediately).
     */
    public long getExpires() {
      return expires;
    }
  }

  /** Validated result from {@link UrlSigner#verify(String, byte[])}. */
  public static class ValidatedSignature {
    private final long expires;

    /**
     * @param expires milliseconds since the epoch when the source {@code signature} expires; {@code
     *     0} indicates "now".
     */
    public ValidatedSignature(long expires) {
      this.expires = expires;
    }

    /**
     * @return milliseconds since the epoch when this signature will expire and no longer be
     *     accepted. If 0 the signature is assumed to expire "quickly" (almost immediately).
     */
    public long getExpires() {
      return expires;
    }
  }
}

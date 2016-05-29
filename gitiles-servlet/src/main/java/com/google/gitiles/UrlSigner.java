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
   * @param url URL to sign. The same string is passed to
   *        {@link #verify(String, byte[])}/
   * @return raw bytes of the authentication token. The caller will encode this
   *         in a web safe format.
   */
  public byte[] sign(String url);

  /**
   * Verify a signature.
   *
   * @param url previously signed URL.
   * @param signature raw bytes of the authentication token.
   * @return true if the signature is valid; false if it has expired or is
   *         invalid in any way.
   */
  public boolean verify(String url, byte[] signature);
}

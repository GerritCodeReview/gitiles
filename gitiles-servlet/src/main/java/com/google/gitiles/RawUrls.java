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

import javax.annotation.Nullable;

/**
 * Supports creation of URLs to raw file content.
 * <p>
 * Per-request instances are created by {@link GitilesAccess}.
 */
public interface RawUrls {
  /** Query parameter carrying the authentication token. */
  public static final String PARAM_KEY = "k";

  /**
   * Construct a URL to access the raw content of a file.
   * <p>
   * The raw URL should be on a different content domain, allowing untrusted
   * content to be served without risking cookies on the primary server.
   * <p>
   * Unless the repository is publicly accessible, raw URLs should include query
   * parameter {@link #PARAM_KEY} created by a {@link UrlSigner}, granting short
   * term access to the raw resource without normal user authentication.
   *
   * @param view a view describing the specific file to access. The revision in
   *        {@code view} should be a full ObjectId and not a branch name.
   * @return raw URL.
   */
  public String createRawUrl(GitilesView view);

  /**
   * Construct a URL to access the raw file redirector.
   * <p>
   * The redirector should be on the standard Gitiles server where it has full
   * access to user authentication data to {@link #createRawUrl(GitilesView)}.
   *
   * @param view a view describing the file to access.
   * @return redirector URL.
   */
  public String createRedirectUrl(GitilesView view);

  /**
   * Validate a key included by {@link #createRawUrl(GitilesView)}.
   * <p>
   * This is called by {@link RawContentServlet} to authenticate the request.
   *
   * @param view a view describing the file being accessed.
   * @param key optional key from the URL. A publicly accessible resource may be
   *        missing the key.
   * @return true if the key grants access for this request.
   */
  public boolean isValidAuthKey(GitilesView view, @Nullable String key);

  /** @return true if the current request arrived on the raw content domain. */
  public boolean isRawDomain();
}

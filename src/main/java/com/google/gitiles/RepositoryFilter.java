// Copyright 2015 Google Inc. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.gitiles.ViewFilter.getRegexGroup;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.eclipse.jgit.http.server.GitSmartHttpTools.sendError;
import static org.eclipse.jgit.http.server.ServletUtils.ATTRIBUTE_REPOSITORY;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

class RepositoryFilter extends AbstractHttpFilter {
  private final RepositoryResolver<HttpServletRequest> resolver;

  RepositoryFilter(RepositoryResolver<HttpServletRequest> resolver) {
    this.resolver = checkNotNull(resolver, "resolver");
  }

  @Override
  public void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    try {
      String repo = ViewFilter.trimLeadingSlash(getRegexGroup(req, 1));
      try (Repository git = resolver.open(req, repo)) {
        req.setAttribute(ATTRIBUTE_REPOSITORY, git);
        chain.doFilter(req, res);
      } catch (RepositoryNotFoundException e) {
        // Drop through the rest of the chain. ViewFilter will pass this
        // to HostIndexServlet which will attempt to list repositories
        // or send SC_NOT_FOUND there.
        chain.doFilter(req, res);
      } catch (ServiceMayNotContinueException e) {
        sendError(req, res, e.getStatusCode(), e.getMessage());
      } finally {
        req.removeAttribute(ATTRIBUTE_REPOSITORY);
      }
    } catch (ServiceNotEnabledException e) {
      sendError(req, res, SC_FORBIDDEN);
    } catch (ServiceNotAuthorizedException e) {
      res.sendError(SC_UNAUTHORIZED);
    }
  }
}

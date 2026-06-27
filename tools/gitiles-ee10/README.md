# Gitiles EE10 Bazel bridge

This package forward-generates the **EE10 (`jakarta.servlet`)** Gitiles flavour
from the canonical **EE8 (`javax.servlet`)** sources, so Gitiles can produce both
flavours from one tree:

* `//java/com/google/gitiles:servlet` — canonical javax.servlet (default),
  links the JGit EE8 servlet bridge.
* `//java/com/google/gitiles:servlet-ee10` — generated jakarta.servlet, links
  the canonical (jakarta) JGit servlet module.

It is the **direction mirror** of JGit's `tools/jgit-ee8` bridge: JGit rewrites
`jakarta.servlet` → `javax.servlet` (its upstream is jakarta-canonical), while
Gitiles rewrites `javax.servlet` → `jakarta.servlet` (its upstream is still
javax-canonical). Only the servlet imports are rewritten; Java package names and
source line numbers are preserved. Bazel-only — Gitiles has no Maven build.

## Same FQDNs — published as a separate flavour, never co-installable

`servlet-ee10` keeps the same exported packages (`com.google.gitiles`) as
`servlet`; the two differ only in the servlet API. They must **never** sit on the
same classpath.

Both flavours are published to `gs://gerrit-maven` under group
`com.google.gitiles` as distinct artifacts (mirroring JGit's first-class `.ee8`
modules):

* `gitiles-servlet` — canonical javax.servlet.
* `gitiles-servlet-ee10` — generated jakarta.servlet.

Because they share Java FQDNs, a consumer must depend on **exactly one** flavour;
they are not co-installable. Gerrit's jakarta WAR consumes `gitiles-servlet-ee10`
(via the gitiles-plugin's EE10 build), the javax WAR consumes `gitiles-servlet`.

## Scope: production servlet library + dev server

The transform covers the production `com.google.gitiles` servlet library, which
is **Jetty-free** (it depends only on the servlet API), plus the **dev server**
in `java/com/google/gitiles/dev`. The dev server pulls Jetty directly and is
**test/dev-only — not part of the published artifact**, but it is built in both
flavours so an EE10 deployment can be exercised locally:

* `//java/com/google/gitiles/dev:dev` — canonical javax / Jetty EE8
  (`tools/run_dev.sh`).
* `//java/com/google/gitiles/dev:dev-ee10` — generated jakarta / Jetty EE10
  (`tools/run_dev_ee10.sh`).

Unlike the production library, the dev server cannot be produced by a pure import
rename: the EE8 and EE10 Jetty `ServletContextHandler` install differently into
the core handler tree (EE8 implements `Supplier<Handler>` and is unwrapped via
`get()`; EE10 *is* a `Handler`). That single structural divergence is isolated
behind the abstract `DevServerBase.toCoreHandler(...)` seam — `DevServerBase` is
transform-generated (servlet + Jetty `ee8 -> ee10` renames via
`tools/gitiles-ee10/rules/dev-javax-to-jakarta-renames.properties`), while each
flavour's `DevServer` overlay (the EE8 one in `dev/`, the EE10 one in `dev/ee10/`)
is hand-written and excluded from the transform. This mirrors JGit's
`AppServerBase` split.

## Run

```sh
# Library: verify the generated sources and build the EE10 servlet library.
bazelisk test //tools/gitiles-ee10:generated_srcs_test
bazelisk build //java/com/google/gitiles:servlet-ee10

# Tests: run the EE10 (jakarta) flavour of the servlet acceptance tests.
bazelisk test //javatests/com/google/gitiles:servlet_tests_ee10

# Dev server: build either flavour, or launch it.
bazelisk build //java/com/google/gitiles/dev:dev        # canonical javax / Jetty EE8
bazelisk build //java/com/google/gitiles/dev:dev-ee10   # generated jakarta / Jetty EE10
./tools/run_dev.sh        # launch the javax dev server
./tools/run_dev_ee10.sh   # launch the jakarta (EE10) dev server
```

`generated_srcs_test` checks that the generated sources:

* are derived from the canonical `//java/com/google/gitiles:srcs` filegroup,
* use Java package paths as srcjar entries,
* preserve line counts (for debugger breakpoints),
* contain `jakarta.servlet`, not `javax.servlet`,
* do not move Gitiles classes to an `.ee10` package.

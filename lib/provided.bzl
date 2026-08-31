"""Wrapper for libraries that Gerrit already ships in gerrit.war.

Standalone Gitiles links these and bundles them into gitiles.war as usual.
When Gitiles is built inside the Gerrit tree
(--@com_googlesource_gerrit_bazlets//flags:in_gerrit_tree=true), they are
switched to neverlink, so the Gitiles Gerrit plugin does not re-package
classes that gerrit.war already provides on the runtime classpath.
"""

load("@rules_java//java:defs.bzl", "java_library")

def provided_java_library(name, exports, runtime_deps = []):
    """java_library that is linked standalone but neverlink inside Gerrit.

    Args:
      name: target name; consumers depend on this directly.
      exports: exported libraries (as for java_library).
      runtime_deps: runtime deps (only relevant to the standalone build).
    """
    java_library(
        name = name,
        exports = exports,
        runtime_deps = runtime_deps,
        neverlink = select({
            "//lib:in_gerrit_tree": 1,
            "//conditions:default": 0,
        }),
        visibility = ["//visibility:public"],
    )

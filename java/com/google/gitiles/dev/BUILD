load("@com_googlesource_gerrit_bazlets//tools:servlet_transform.bzl", javax_to_jakarta_srcjar = "transform_srcjar")
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

exports_files(
    ["DevServerBase.java"],
    visibility = ["//tools/gitiles-ee10:__pkg__"],
)

# Canonical EE8 (javax.servlet) dev server. The glob does not descend into the
# ee10/ subpackage (it has its own BUILD), so it covers DevServerBase.java, the
# EE8 DevServer.java overlay, and Main.java.
java_library(
    name = "lib",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gitiles:servlet",
        "//lib:guava",
        "//lib:guava-failureaccess",
        "//lib:html-types",
        "//lib:jgit",
        "//lib:jgit-servlet",
        "//lib:servlet-api",
        "//lib:slf4j-api",
        "//lib:slf4j-simple",
        "//lib/jetty:server",
        "//lib/jetty:servlet",
        "//lib/soy",
    ],
)

java_binary(
    name = "dev",
    main_class = "com.google.gitiles.dev.Main",
    runtime_deps = [":lib"],
)

# --- EE10 (jakarta.servlet) forward-generated dev server --------------------
# DevServerBase is the only flavour-coupled dev code; it is rewritten javax ->
# jakarta and ee8 -> ee10 by the shared transform. The structural divergence
# (ServletContextHandler install) lives in the hand-written DevServer overlays,
# which are excluded from the transform: the EE8 one in this package, the EE10
# one under ee10/. Main.java is flavour-neutral and compiled directly in both.

javax_to_jakarta_srcjar(
    name = "devserver-base-ee10-srcs",
    direction = "to_jakarta",
    sources = ["DevServerBase.java"],
    src_prefix = "java/",
    visibility = ["//tools/gitiles-ee10:__pkg__"],
)

java_library(
    name = "lib-ee10",
    srcs = [
        "Main.java",
        ":devserver-base-ee10-srcs",
        "//java/com/google/gitiles/dev/ee10:overlay",
    ],
    visibility = ["//visibility:public"],
    deps = [
        "//java/com/google/gitiles:servlet-ee10",
        "//lib:guava",
        "//lib:guava-failureaccess",
        "//lib:html-types",
        "//lib:jakarta-servlet-api",
        "//lib:jgit",
        "//lib:jgit-servlet-jakarta",
        "//lib:slf4j-api",
        "//lib:slf4j-simple",
        "//lib/jetty:server",
        "//lib/jetty:servlet-ee10",
        "//lib/soy",
    ],
)

java_binary(
    name = "dev-ee10",
    main_class = "com.google.gitiles.dev.Main",
    runtime_deps = [":lib-ee10"],
)

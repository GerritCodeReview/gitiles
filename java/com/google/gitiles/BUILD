load("@com_googlesource_gerrit_bazlets//tools:javadoc.bzl", "java_doc")
load("@com_googlesource_gerrit_bazlets//tools:servlet_transform.bzl", "transform_srcjar")
load("@rules_java//java:defs.bzl", "java_library")
load("//tools:stamper.bzl", "stamp")

DEPS = [
    "//java/com/google/gitiles/blame/cache",
    "//lib:autolink",
    "//lib:cm-autolink",
    "//lib:commonmark",
    "//lib:commons-io",
    "//lib:commons-lang3",
    "//lib:commons-text",
    "//lib:errorprone-annotations",
    "//lib/flogger",
    "//lib:gfm-strikethrough",
    "//lib:gfm-tables",
    "//lib:gson",
    "//lib:guava",
    "//lib:html-types",
    "//lib:jgit",
    "//lib:jgit-servlet",
    "//lib:jsr305",
    "//lib:prettify",
    "//lib:slf4j-api",
    "//lib/soy",
]

DEPS_ALL = DEPS + [
    "//lib:jgit-archive",
]

java_library(
    name = "servlet-api",
    neverlink = 1,
    exports = ["//lib:servlet-api"],
)

# Canonical jakarta library. Naming follows JGit's reversed bridge: the
# unsuffixed name is the canonical jakarta form (released as
# gitiles-servlet 2.0.0); the "-ee8" suffix marks the generated javax
# backward bridge.
java_library(
    name = "servlet",
    srcs = glob(["**/*.java"]),
    resource_strip_prefix = "resources",
    resources = ["//resources/com/google/gitiles"],
    visibility = ["//visibility:public"],
    deps = DEPS_ALL + [
        ":servlet-api",
    ],
)

java_doc(
    name = "servlet-javadoc",
    libs = DEPS + [
        ":servlet",
    ],
    pkgs = ["com.google.gitiles"],
    title = "Gitiles API Documentation",
    visibility = ["//visibility:public"],
)

stamp(
    name = "servlet",
    workspace = "gitiles",
)

# EE8 (javax.servlet) backward bridge: the canonical sources are jakarta;
# this generates the javax twin through the shared to_javax transform, so
# javax consumers -- Gerrit's EE8 default WAR and javax-bound deployments of
# Gitiles itself -- keep tracking current Gitiles code. Published as the
# gitiles-servlet-ee8 artifact, mirroring JGit's reversed bridge
# (org.eclipse.jgit.http.server.ee8); the canonical jakarta library keeps
# the unsuffixed gitiles-servlet name it was released under in 2.0.0.
DEPS_EE8 = [dep if dep != "//lib:jgit-servlet" else "//lib:jgit-servlet-ee8" for dep in DEPS]

java_library(
    name = "servlet-api-ee8",
    neverlink = 1,
    exports = ["//lib:servlet-api-ee8"],
)

transform_srcjar(
    name = "servlet-ee8-srcs",
    direction = "to_javax",
    sources = glob(["**/*.java"]),
    src_prefix = "java/",
)

java_library(
    name = "servlet-ee8",
    srcs = [":servlet-ee8-srcs"],
    resource_strip_prefix = "resources",
    resources = ["//resources/com/google/gitiles"],
    visibility = ["//visibility:public"],
    deps = DEPS_EE8 + [
        ":servlet-api-ee8",
        "//lib:jgit-archive",
    ],
)

java_doc(
    name = "servlet-ee8-javadoc",
    libs = DEPS_EE8 + [":servlet-ee8"],
    pkgs = ["com.google.gitiles"],
    title = "Gitiles API Documentation",
    visibility = ["//visibility:public"],
)

stamp(
    name = "servlet-ee8",
    workspace = "gitiles",
)

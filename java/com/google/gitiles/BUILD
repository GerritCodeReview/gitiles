load("@com_googlesource_gerrit_bazlets//tools:javadoc.bzl", "java_doc")
load("@rules_java//java:defs.bzl", "java_library")
load("//tools:stamper.bzl", "stamp")
# Shared servlet-flavour transform from bazlets; the to_jakarta direction
# (javax -> jakarta) is built in, so no renames data lives here.
load("@com_googlesource_gerrit_bazlets//tools:servlet_transform.bzl", javax_to_jakarta_srcjar = "transform_srcjar")

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

# EE10 (jakarta.servlet) variant of DEPS_ALL: swap the JGit EE8 servlet bridge
# for the canonical jakarta.servlet JGit servlet module. Everything else is
# servlet-neutral and shared.
DEPS_EE10_ALL = [
    "//lib:jgit-servlet-jakarta" if d == "//lib:jgit-servlet" else d
    for d in DEPS_ALL
]

filegroup(
    name = "srcs",
    srcs = glob(["**/*.java"]),
    visibility = ["//visibility:public"],
)

java_library(
    name = "servlet-api",
    neverlink = 1,
    exports = ["//lib:servlet-api"],
)

java_library(
    name = "servlet",
    srcs = [":srcs"],
    resource_strip_prefix = "resources",
    resources = ["//resources/com/google/gitiles"],
    visibility = ["//visibility:public"],
    deps = DEPS_ALL + [
        ":servlet-api",
    ],
)

# --- EE10 (jakarta.servlet) forward-generated flavour -----------------------
# Generated from the canonical javax.servlet sources by rewriting only the
# servlet imports (javax.servlet -> jakarta.servlet), preserving Java package
# names and source line numbers. Same FQDNs as :servlet, so the two must never
# share a classpath. See tools/gitiles-ee10/README.md.

java_library(
    name = "servlet-api-ee10",
    neverlink = 1,
    exports = ["//lib:jakarta-servlet-api"],
)

javax_to_jakarta_srcjar(
    name = "servlet-ee10-srcs",
    direction = "to_jakarta",
    sources = [":srcs"],
    src_prefix = "java/",
    visibility = ["//visibility:public"],
)

filegroup(
    name = "servlet-ee10-srcjar",
    srcs = [":servlet-ee10-srcs"],
    visibility = ["//visibility:public"],
)

java_library(
    name = "servlet-ee10",
    srcs = [":servlet-ee10-srcs"],
    resource_strip_prefix = "resources",
    resources = ["//resources/com/google/gitiles"],
    visibility = ["//visibility:public"],
    deps = DEPS_EE10_ALL + [
        ":servlet-api-ee10",
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

# EE10 (jakarta.servlet) publish inputs, mirroring the canonical ones above.
# Published as the distinct artifact gitiles-servlet-ee10 (see tools/maven). The
# auto-generated :libservlet-ee10-src.jar serves as the src jar.
java_doc(
    name = "servlet-ee10-javadoc",
    libs = DEPS_EE10_ALL + [
        ":servlet-ee10",
    ],
    pkgs = ["com.google.gitiles"],
    title = "Gitiles API Documentation (EE10)",
    visibility = ["//visibility:public"],
)

stamp(
    name = "servlet-ee10",
    workspace = "gitiles",
)

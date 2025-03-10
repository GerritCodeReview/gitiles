# Tests for Gitiles

load("//testing/build_defs:junit4_test_suites.bzl", "junit4_test_suites")
load("//third_party/bazel_rules/rules_java/java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//third_party/java_src/gitiles:license"],
    default_visibility = ["//visibility:private"],
)

licenses(["notice"])

java_library(
    name = "tests",
    testonly = 1,
    srcs = glob(["**/*.java"]),
    javacopts = [
    ],
    deps = [
        "//java/com/google/common/base",
        "//java/com/google/common/cache",
        "//java/com/google/common/collect",
        "//java/com/google/common/flags",
        "//java/com/google/common/io",
        "//java/com/google/common/net",
        "//java/com/google/common/util/concurrent",
        "//java/com/google/testing/testsize:annotations",
        "//third_party/java/gson",
        "//third_party/java/jgit",
        "//third_party/java/jgit:jgit-junit",
        "//third_party/java/jgit:jgit-servlet",
        "//third_party/java/joda_time",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/junit",
        "//third_party/java/servlet/servlet_api",
        "//third_party/java/truth",
        "//third_party/java_src/gitiles:config_util",
        "//third_party/java_src/gitiles:servlet",
        "//third_party/java_src/soy/java/com/google/template/soy/data",
    ],
)

junit4_test_suites(
    name = "gen_tests",
    timeout = "moderate",
    sizes = ["small",
             "medium",
    ],
    runtime_deps = ["//java/com/google/experiments/framework/options:exempt_existing_client_java"],
    deps = [":tests"],
)

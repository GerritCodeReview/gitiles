load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "truth",
    visibility = ["//visibility:public"],
    exports = ["@external_deps//:com_google_truth_truth"],
    runtime_deps = [
        ":diffutils",
        "//lib:guava",
        "//lib/junit",
    ],
)

java_library(
    name = "diffutils",
    visibility = ["//visibility:private"],
    exports = ["@external_deps//:io_github_java_diff_utils_java_diff_utils"],
)

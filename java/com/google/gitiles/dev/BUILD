load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "lib",
    srcs = glob(["**/*.java"]),
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

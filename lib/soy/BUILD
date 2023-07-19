load("@rules_java//java:defs.bzl", "java_library")

package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "soy",
    exports = [
        "@soy//jar",
    ],
    runtime_deps = [
        "@flogger-google-extensions//jar",
        "@flogger-log4j-backend//jar",
        "@flogger-system-backend//jar",
        "@flogger//jar",
        "@html-types//jar",
        "@icu4j//jar",
        "@log4j//jar",
        "@ow2-asm-analysis//jar",
        "@ow2-asm-commons//jar",
        "@ow2-asm-tree//jar",
        "@ow2-asm-util//jar",
        "@ow2-asm//jar",
        "@protobuf//jar",
    ],
)

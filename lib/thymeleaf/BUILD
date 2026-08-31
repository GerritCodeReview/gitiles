load("@rules_java//java:defs.bzl", "java_library")

package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "thymeleaf",
    exports = [
        "@external_deps//:org_thymeleaf_thymeleaf",
    ],
    runtime_deps = [
        "@external_deps//:ognl_ognl",
        "@external_deps//:org_attoparser_attoparser",
        "@external_deps//:org_javassist_javassist",
        "@external_deps//:org_slf4j_slf4j_api",
        "@external_deps//:org_unbescape_unbescape",
    ],
)

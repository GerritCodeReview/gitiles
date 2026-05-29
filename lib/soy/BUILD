load("@rules_java//java:defs.bzl", "java_library")

package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "soy",
    exports = [
        "@external_deps//:com_google_template_soy",
    ],
    runtime_deps = [
        "@external_deps//:com_google_common_html_types_types",
        "@external_deps//:com_google_protobuf_protobuf_java",
        "@external_deps//:com_ibm_icu_icu4j",
        "@external_deps//:org_ow2_asm_asm",
        "@external_deps//:org_ow2_asm_asm_analysis",
        "@external_deps//:org_ow2_asm_asm_commons",
        "@external_deps//:org_ow2_asm_asm_tree",
        "@external_deps//:org_ow2_asm_asm_util",
    ],
)

package(
    default_visibility = ["//visibility:public"],
)

OW_VERSION = "5.0.3"

[java_library(
    name = n,
    exports = ["@%s//jar" % n.replace("-", "_")],
) for n in [
    "ow2-asm",
    "ow2-asm-analysis",
    "ow2-asm-tree",
    "ow2-asm-util",
]]

package(
    default_visibility = ["//visibility:public"],
)

[java_library(
    name = n,
    exports = ["@%s//jar" % n.replace("-", "_")],
) for n in [
    "commons-lang",
    "jsr305",
    "jgit-archive-library",
    "joda-time",
#    "servlet-api_2_5",
    "servlet-api_3_0",
    "truth",
    "gson",
    "guava",
    "prettify",
    "jitescript",
]]

java_library(
    name = "pegdown",
    exports = [
        "@pegdown//jar",
        ":grappa",
    ],
)

java_library(
    name = "grappa",
    exports = [
        "@grappa//jar",
    ],
    runtime_deps = [
        ":guava",
        ":jitescript",
        "//lib/ow2:ow2-asm",
        "//lib/ow2:ow2-asm-analysis",
        "//lib/ow2:ow2-asm-tree",
        "//lib/ow2:ow2-asm-util",
    ],
)

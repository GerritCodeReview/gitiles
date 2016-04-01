package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "servlet",
    exports = [
        "@servlet//jar",
        ":security",
        "//lib:servlet-api_3_0",  # Different from the rest of gitiles-server.
    ],
)

java_library(
    name = "security",
    exports = [
        "@security//jar",
        ":server",
    ],
)

java_library(
    name = "server",
    exports = [
        "@server//jar",
        ":continuation",
        ":http",
    ],
)

java_library(
    name = "continuation",
    exports = ["@continuation//jar"],
)

java_library(
    name = "http",
    exports = [
        "@http//jar",
        ":io",
    ],
)

java_library(
    name = "io",
    exports = [
        "@io//jar",
        ":util",
    ],
)

java_library(
    name = "util",
    exports = ["@util//jar"],
)

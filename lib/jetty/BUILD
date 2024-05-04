load("@rules_java//java:defs.bzl", "java_library")

package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "servlet",
    exports = [
        ":security",
        "//lib:servlet-api",
        "@external_deps//:org_eclipse_jetty_ee10_jetty_ee10_servlet",
    ],
)

java_library(
    name = "security",
    exports = [
        ":server",
        "@external_deps//:org_eclipse_jetty_jetty_security",
    ],
)

java_library(
    name = "server",
    exports = [
        ":http",
        "@external_deps//:org_eclipse_jetty_jetty_server",
    ],
)

java_library(
    name = "http",
    exports = [
        ":io",
        "@external_deps//:org_eclipse_jetty_jetty_http",
    ],
)

java_library(
    name = "io",
    exports = [
        ":util",
        "@external_deps//:org_eclipse_jetty_jetty_io",
    ],
)

java_library(
    name = "util",
    exports = ["@external_deps//:org_eclipse_jetty_jetty_util"],
)

load("@com_googlesource_gerrit_bazlets//tools:classpath.bzl", "classpath_collector")

DEPS = [
    "//gitiles-servlet:servlet",
    "//gitiles-dev:lib",
]

java_library(
    name = "classpath",
    runtime_deps = DEPS,
)

classpath_collector(
    name = "main_classpath_collect",
    deps = DEPS + [
        "//gitiles-servlet:servlet_tests",
    ],
    testonly = 1,
)

genrule(
    name = "gen_project_location",
    outs = ["gen_project_location.txt"],
    cmd = ("which $(location @com_googlesource_gerrit_bazlets//tools/eclipse:project) > $@"),
    stamp = 1,
    tools = ["@com_googlesource_gerrit_bazlets//tools/eclipse:project"],
    executable = 1,
    testonly = 1,
    local = 1,
)

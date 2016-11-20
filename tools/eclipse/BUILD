load("@com_github_davido_bazlets//tools:classpath.bzl", "classpath_collector")

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

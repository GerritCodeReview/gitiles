load("@com_googlesource_gerrit_bazlets//tools:junit.bzl", "junit_tests")
load("@rules_java//java:defs.bzl", "java_library")

DEPS = [
    "//lib:gson",
    "//lib:guava",
    "//lib:guava-failureaccess",
    "//lib:jgit",
    "//lib:jgit-servlet",
    "//lib/flogger",
    "//lib/soy",
]

java_library(
    name = "testutil",
    srcs = glob(
        ["**/*.java"],
        exclude = ["**/*Test.java"],
    ) + glob(["**/ServletTest.java"]),
    deps = DEPS + [
        "//java/com/google/gitiles:servlet",
        "//lib:jgit-junit",
        "//lib:jsr305",
        "//lib:servlet-api",
        "//lib/junit",
        "//lib/truth",
    ],
)

junit_tests(
    name = "servlet_tests",
    size = "small",
    srcs = glob(
        [
            "**/*Test.java",
        ],
        exclude = ["**/ServletTest.java"],
    ),
    visibility = ["//visibility:public"],
    runtime_deps = ["//lib/junit:hamcrest"],
    deps = DEPS + [
        ":testutil",
        "//java/com/google/gitiles:servlet",
        "//java/com/google/gitiles/blame/cache",
        "//java/com/google/gitiles/diff:intraline-diff",
        "//lib:commonmark",
        "//lib:commons-codec",
        "//lib:commons-io",
        "//lib:html-types",
        "//lib:jgit-junit",
        "//lib:servlet-api",
        "//lib/junit",
        "//lib/truth",
    ],
)

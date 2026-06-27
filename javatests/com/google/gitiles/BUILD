# Shared servlet-flavour transform rule and junit macro from bazlets; the
# to_jakarta direction (javax -> jakarta) is built in, no renames data here.
load("@com_googlesource_gerrit_bazlets//tools:junit.bzl", "junit_tests")
load("@com_googlesource_gerrit_bazlets//tools:servlet_transform.bzl", javax_to_jakarta_srcjar = "transform_srcjar")
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

# EE10 (jakarta.servlet) variant of DEPS: swap the JGit EE8 servlet bridge for
# the canonical jakarta.servlet JGit servlet module.
DEPS_EE10 = [
    "//lib:jgit-servlet-jakarta" if d == "//lib:jgit-servlet" else d
    for d in DEPS
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
        "//lib:commons-codec",
        "//lib:commons-io",
        "//lib:jgit-junit",
        "//lib:servlet-api",
        "//lib/junit",
        "//lib/truth",
    ],
)

# --- EE10 (jakarta.servlet) forward-generated test flavour ------------------
# The test sources (including the FakeHttpServletRequest/Response helpers, which
# implement the servlet interfaces) are forward-generated javax -> jakarta and
# compiled against //java/com/google/gitiles:servlet-ee10. The tests use no real
# Jetty (only the Fakes), so no Jetty EE10 dependency is required.

# Transform input: the canonical testutil sources MINUS the two fakes. The fakes
# can't survive a pure import rename (Servlet 4 <-> 6 API drift), so they are
# replaced by the hand-written jakarta overrides in
# //javatests/com/google/gitiles/ee10:fakes.
filegroup(
    name = "testutil-srcs",
    srcs = glob(
        ["**/*.java"],
        exclude = [
            "**/*Test.java",
            "FakeHttpServletRequest.java",
            "FakeHttpServletResponse.java",
        ],
    ) + glob(["**/ServletTest.java"]),
    visibility = ["//tools/gitiles-ee10:__pkg__"],
)

filegroup(
    name = "test-srcs",
    srcs = glob(
        ["**/*Test.java"],
        exclude = ["**/ServletTest.java"],
    ),
    visibility = ["//tools/gitiles-ee10:__pkg__"],
)

javax_to_jakarta_srcjar(
    name = "testutil-ee10-srcs",
    direction = "to_jakarta",
    sources = [":testutil-srcs"],
    src_prefix = "javatests/",
    visibility = ["//tools/gitiles-ee10:__pkg__"],
)

javax_to_jakarta_srcjar(
    name = "test-ee10-srcs",
    direction = "to_jakarta",
    sources = [":test-srcs"],
    src_prefix = "javatests/",
    visibility = ["//tools/gitiles-ee10:__pkg__"],
)

java_library(
    name = "testutil-ee10",
    testonly = 1,
    srcs = [
        ":testutil-ee10-srcs",
        "//javatests/com/google/gitiles/ee10:fakes",
    ],
    deps = DEPS_EE10 + [
        "//java/com/google/gitiles:servlet-ee10",
        "//lib:jakarta-servlet-api",
        "//lib:jgit-junit",
        "//lib:jsr305",
        "//lib/junit",
        "//lib/truth",
    ],
)

junit_tests(
    name = "servlet_tests_ee10",
    size = "small",
    suite_srcs = [":test-srcs"],
    srcs = [":test-ee10-srcs"],
    visibility = ["//visibility:public"],
    runtime_deps = ["//lib/junit:hamcrest"],
    deps = DEPS_EE10 + [
        ":testutil-ee10",
        "//java/com/google/gitiles:servlet-ee10",
        "//lib:commons-codec",
        "//lib:commons-io",
        "//lib:jakarta-servlet-api",
        "//lib:jgit-junit",
        "//lib/junit",
        "//lib/truth",
    ],
)

java_library(
    name = "servlet",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**/*"]),
    visibility = ["//visibility:public"],
    deps = [
        "//lib:commons-lang",
        "//lib:grappa",
        "//lib:gson",
        "//lib:guava",
        "//lib:joda-time",
        "//lib:jsr305",
        "//lib:pegdown",
        "//lib:prettify",
        "//lib:servlet-api_3_0",
        "//lib/guice",
        "//lib/jgit",
        "//lib/jgit:jgit-archive",
        "//lib/jgit:jgit-servlet",
        "//lib/slf4j:slf4j-api",
        "//lib/soy",
    ],
)

java_library(
    name = "testutil",
    srcs = glob(
        ["src/test/java/**/*.java"],
        exclude = ["src/test/java/**/*Test.java"],
    ) + glob(["**/ServletTest.java"]),
    deps = [
        ":servlet",
        "//lib:gson",
        "//lib:guava",
        "//lib:servlet-api_3_0",
        "//lib:truth",
        "//lib/jgit",
        "//lib/jgit:jgit-servlet",
        "//lib/jgit:junit",
        "//lib/junit",
    ],
)

java_test(
    name = "servlet_tests",
    srcs = glob(
        ["src/test/java/**/*Test.java"],
        exclude = ["**/ServletTest.java"],
    ),
    runtime_deps = ["//lib/junit:hamcrest-core"],
    deps = [
        ":servlet",
        ":testutil",
        "//lib:grappa",
        "//lib:gson",
        "//lib:guava",
        "//lib:joda-time",
        "//lib:servlet-api_2_5",
        "//lib:truth",
        "//lib/jgit",
        "//lib/jgit:jgit-servlet",
        "//lib/jgit:junit",
        "//lib/junit",
        "//lib/soy",
    ],
)

genrule(
    name = "static-resources",
    srcs = glob(["src/main/resources/com/google/gitiles/static/**/*"]),
    outs = ["static-resources.zip"],
    cmd = "ROOT=$$PWD ; TMP=$$(mktemp -d) && mkdir $$TMP/+static" +
          " && tar -cf- $(SRCS) | tar -xf- --strip-components=7 -C $$TMP/+static/ " +
          " && cd $$TMP" +
          " && zip -qr $$ROOT/$@ *",
    visibility = ["//visibility:public"],
)

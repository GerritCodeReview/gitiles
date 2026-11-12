load(
    "@com_googlesource_gerrit_bazlets//tools:genrule2.bzl",
    "genrule2",
)
load("//tools:jar.bzl", "JAR_CREATE", "JAR_SETUP", "JAR_TOOLCHAINS")

filegroup(
    name = "gitiles",
    srcs = glob(
        ["**/*"],
        exclude = ["BUILD"],
    ),
    visibility = ["//visibility:public"],
)

genrule2(
    name = "webassets",
    srcs = [":gitiles"],
    outs = ["webassets.zip"],
    cmd = " && ".join([
        JAR_SETUP,
        "o=$$PWD/$@",
        "mkdir -p $$TMP/com/google/gitiles",
        "for f in $(SRCS); do " +
        "rel=$${f#*resources/com/google/gitiles/}; " +
        "mkdir -p $$TMP/com/google/gitiles/$$(dirname $$rel); " +
        "cp $$f $$TMP/com/google/gitiles/$$rel; " +
        "done",
        "cd $$TMP/com/google/gitiles/",
        "mv static +static",
        JAR_CREATE + " --file $$o .",
    ]),
    toolchains = JAR_TOOLCHAINS,
    visibility = ["//visibility:public"],
)

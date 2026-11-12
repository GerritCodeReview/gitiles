# TODO(davido): Consider to move this general bazlets to Bazlets repository
load("@com_googlesource_gerrit_bazlets//tools:genrule2.bzl", "genrule2")
load("//tools:jar.bzl", "JAR_CREATE", "JAR_SETUP", "JAR_TOOLCHAINS")

def stamp(workspace, name):
    # TODO(davido): Remove manual merge of manifest file when this feature
    # request is implemented: https://github.com/bazelbuild/bazel/issues/2009
    genrule2(
        name = "%s-stamped" % name,
        stamp = 1,
        srcs = [":%s" % name],
        cmd = " && ".join([
            JAR_SETUP,
            "GEN_VERSION=$$(cat bazel-out/stable-status.txt | grep -w STABLE_BUILD_%s_LABEL | cut -d ' ' -f 2)" % workspace.upper(),
            "cd $$TMP",
            "$$JAR xf $$ROOT/$<",
            "echo \"Implementation-Version: $$GEN_VERSION\n$$(cat META-INF/MANIFEST.MF)\" > META-INF/MANIFEST.MF",
            JAR_CREATE + " --file $$ROOT/$@ .",
        ]),
        toolchains = JAR_TOOLCHAINS,
        outs = ["%s-stamped.jar" % name],
        visibility = ["//visibility:public"],
    )

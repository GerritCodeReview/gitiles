load("@com_googlesource_gerrit_bazlets//tools:pkg_war.bzl", "pkg_war")

pkg_war(
    name = "gitiles",
    context = ["//resources/com/google/gitiles:webassets"],
    exclude_jar_prefixes = ["libjgit.jar"],
    libs = [
        "@jgit//org.eclipse.jgit:jgit-stamped",
        "//lib/jetty:server",
        "//lib/jetty:servlet",
        "//lib:slf4j-simple",
        "//lib:guava-failureaccess",
        "//java/com/google/gitiles:servlet",
    ],
    web_xml = "//resources:web_xml",
)

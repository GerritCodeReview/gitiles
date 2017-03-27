load("@com_googlesource_gerrit_bazlets//tools:pkg_war.bzl", "pkg_war")

pkg_war(
    name = "gitiles",
    libs = [
        "//gitiles-servlet:servlet",
        "//lib:guava",
        "//lib/jetty:server",
        "//lib/jetty:servlet",
        "//lib/jgit",
        "//lib/jgit:jgit-servlet",
        "//lib/slf4j:slf4j-api",
        "//lib/slf4j:slf4j-simple",
        "//lib/soy",
    ],
)

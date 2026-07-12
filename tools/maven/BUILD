load("@com_googlesource_gerrit_bazlets//tools/maven:package.bzl", "maven_package")
load("//:version.bzl", "GITILES_VERSION")

# The unsuffixed gitiles-servlet artifact is the jakarta library (as
# released in 2.0.0). The gitiles-servlet-ee8 backward bridge is retired;
# javax consumers stay on its last release.
maven_package(
    src = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:libcache-src.jar",
        "gitiles-servlet": "//java/com/google/gitiles:libservlet-src.jar",
    },
    doc = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:cache-javadoc",
        "gitiles-servlet": "//java/com/google/gitiles:servlet-javadoc",
    },
    group = "com.google.gitiles",
    jar = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:cache-stamped",
        "gitiles-servlet": "//java/com/google/gitiles:servlet-stamped",
    },
    repository = "gerrit-maven-repository",
    url = "gs://gerrit-maven",
    version = GITILES_VERSION,
)

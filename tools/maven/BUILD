load("@com_googlesource_gerrit_bazlets//tools/maven:package.bzl", "maven_package")
load("//:version.bzl", "GITILES_VERSION")

maven_package(
    src = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:libcache-src.jar",
        "gitiles-servlet": "//java/com/google/gitiles:libservlet-src.jar",
        "gitiles-servlet-ee10": "//java/com/google/gitiles:libservlet-ee10-src.jar",
    },
    doc = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:cache-javadoc",
        "gitiles-servlet": "//java/com/google/gitiles:servlet-javadoc",
        "gitiles-servlet-ee10": "//java/com/google/gitiles:servlet-ee10-javadoc",
    },
    group = "com.google.gitiles",
    jar = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:cache-stamped",
        "gitiles-servlet": "//java/com/google/gitiles:servlet-stamped",
        "gitiles-servlet-ee10": "//java/com/google/gitiles:servlet-ee10-stamped",
    },
    repository = "gerrit-maven-repository",
    url = "gs://gerrit-maven",
    version = GITILES_VERSION,
)

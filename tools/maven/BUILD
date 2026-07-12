load("@com_googlesource_gerrit_bazlets//tools/maven:package.bzl", "maven_package")
load("//:version.bzl", "GITILES_VERSION")

# Artifact naming follows JGit's scheme: the unsuffixed gitiles-servlet
# artifact is the canonical jakarta library (as released in 2.0.0); the
# gitiles-servlet-ee8 artifact is the javax backward bridge for consumers
# still on Servlet API 3.1/4.0.
maven_package(
    src = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:libcache-src.jar",
        "gitiles-servlet": "//java/com/google/gitiles:libservlet-src.jar",
        "gitiles-servlet-ee8": "//java/com/google/gitiles:libservlet-ee8-src.jar",
    },
    doc = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:cache-javadoc",
        "gitiles-servlet": "//java/com/google/gitiles:servlet-javadoc",
        "gitiles-servlet-ee8": "//java/com/google/gitiles:servlet-ee8-javadoc",
    },
    group = "com.google.gitiles",
    jar = {
        "blame-cache": "//java/com/google/gitiles/blame/cache:cache-stamped",
        "gitiles-servlet": "//java/com/google/gitiles:servlet-stamped",
        "gitiles-servlet-ee8": "//java/com/google/gitiles:servlet-ee8-stamped",
    },
    repository = "gerrit-maven-repository",
    url = "gs://gerrit-maven",
    version = GITILES_VERSION,
)

load("//:version.bzl", "GITILES_VERSION")
load("@com_googlesource_gerrit_bazlets//tools/maven:package.bzl", "maven_package")

MAVEN_REPOSITORY = "sonatype-nexus-staging"
MAVEN_RELEASE_URL = "https://oss.sonatype.org/service/local/staging/deploy/maven2"

maven_package(
    src = {
        "blame-cache": "//blame-cache:liblib-src.jar",
        "gitiles-servlet": "//gitiles-servlet:libservlet-src.jar",
    },
    doc = {
        "blame-cache": "//blame-cache:javadoc",
        "gitiles-servlet": "//gitiles-servlet:javadoc",
    },
    jar = {
        "blame-cache": "//blame-cache:lib",
        "gitiles-servlet": "//gitiles-servlet:servlet",
    },
    repository = MAVEN_REPOSITORY,
    url = MAVEN_RELEASE_URL,
    version = GITILES_VERSION,
)

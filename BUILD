load("@com_googlesource_gerrit_bazlets//tools:pkg_war.bzl", "pkg_war")
load("@com_googlesource_gerrit_bazlets//tools:sbom_scan.bzl", "sbom_scan")

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

# CycloneDX SBOM of the gitiles WAR's bundled dependencies, plus an opt-in
# Trivy scan. Generate with  bazel build //:gitiles_sbom  and scan with
# bazel test //:gitiles_sbom_scan. Add a .trivyignore next to this BUILD to
# suppress a CVE with no upstream fix yet, or one not reachable from gitiles.
sbom_scan(
    name = "gitiles",
    target = ":gitiles",
)

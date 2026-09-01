load("@rules_java//java:defs.bzl", "java_library")
load("//lib:provided.bzl", "provided_java_library")

package(
    default_visibility = ["//visibility:public"],
)

# True when Gitiles is built inside the Gerrit source tree; Gerrit sets
# --@com_googlesource_gerrit_bazlets//flags:in_gerrit_tree=true in its .bazelrc.
# Standalone Gitiles never sets it, so it defaults to false.
config_setting(
    name = "in_gerrit_tree",
    flag_values = {"@com_googlesource_gerrit_bazlets//flags:in_gerrit_tree": "true"},
    visibility = ["//visibility:public"],
)

# Libraries that are NOT shipped in gerrit.war; the Gitiles plugin bundles them.
[java_library(
    name = local_name,
    exports = [coord],
) for local_name, coord in [
    ("autolink", "@external_deps//:org_nibor_autolink_autolink"),
    ("commonmark", "@external_deps//:org_commonmark_commonmark"),
    ("commons-codec", "@external_deps//:commons_codec_commons_codec"),
    ("cm-autolink", "@external_deps//:org_commonmark_commonmark_ext_autolink"),
    ("gfm-strikethrough", "@external_deps//:org_commonmark_commonmark_ext_gfm_strikethrough"),
    ("gfm-tables", "@external_deps//:org_commonmark_commonmark_ext_gfm_tables"),
    ("cm-yaml-front-matter", "@external_deps//:org_commonmark_commonmark_ext_yaml_front_matter"),
    ("servlet-api", "@external_deps//:javax_servlet_javax_servlet_api"),
    ("guava-failureaccess", "@external_deps//:com_google_guava_failureaccess"),
    ("ow2-asm", "@external_deps//:org_ow2_asm_asm"),
    ("ow2-asm-analysis", "@external_deps//:org_ow2_asm_asm_analysis"),
    ("ow2-asm-commons", "@external_deps//:org_ow2_asm_asm_commons"),
    ("ow2-asm-tree", "@external_deps//:org_ow2_asm_asm_tree"),
    ("ow2-asm-util", "@external_deps//:org_ow2_asm_asm_util"),
]]

# Libraries already shipped in gerrit.war: linked standalone, neverlink in-tree.
[provided_java_library(
    name = local_name,
    exports = [coord],
) for local_name, coord in [
    ("commons-io", "@external_deps//:commons_io_commons_io"),
    ("commons-lang3", "@external_deps//:org_apache_commons_commons_lang3"),
    ("commons-text", "@external_deps//:org_apache_commons_commons_text"),
    ("html-types", "@external_deps//:com_google_common_html_types_types"),
    ("jsr305", "@external_deps//:com_google_code_findbugs_jsr305"),
    ("gson", "@external_deps//:com_google_code_gson_gson"),
    ("guava", "@external_deps//:com_google_guava_guava"),
    ("errorprone-annotations", "@external_deps//:com_google_errorprone_error_prone_annotations"),
]]

java_library(
    name = "prettify",
    visibility = ["//visibility:public"],
    exports = ["@java-prettify"],
)

provided_java_library(
    name = "slf4j-api",
    exports = ["@external_deps//:org_slf4j_slf4j_api"],
)

java_library(
    name = "slf4j-simple",
    runtime_deps = [
        ":slf4j-api",
        "@external_deps//:org_slf4j_slf4j_simple",
    ],
)

provided_java_library(
    name = "jgit",
    exports = ["@jgit//org.eclipse.jgit:jgit"],
    runtime_deps = [
        ":slf4j-api",
        "@external_deps//:com_googlecode_javaewah_JavaEWAH",
    ],
)

provided_java_library(
    name = "jgit-archive",
    exports = ["@jgit//org.eclipse.jgit.archive:jgit-archive"],
    runtime_deps = [":jgit"],
)

java_library(
    name = "jgit-junit",
    testonly = True,
    visibility = ["//visibility:public"],
    exports = ["@jgit//org.eclipse.jgit.junit:junit"],
    runtime_deps = [":jgit"],
)

provided_java_library(
    name = "jgit-servlet",
    exports = ["@jgit//org.eclipse.jgit.http.server.ee8:jgit-servlet-ee8"],
    runtime_deps = [":jgit"],
)

java_library(
    name = "tukaani-xz",
    exports = ["@external_deps//:org_tukaani_xz"],
)

java_library(
    name = "commons-compress",
    exports = ["@external_deps//:org_apache_commons_commons_compress"],
)

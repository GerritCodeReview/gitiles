load("@rules_java//java:defs.bzl", "java_library")

package(
    default_visibility = ["//visibility:public"],
)

[java_library(
    name = local_name,
    exports = [coord],
) for local_name, coord in [
    ("autolink", "@external_deps//:org_nibor_autolink_autolink"),
    ("commonmark", "@external_deps//:org_commonmark_commonmark"),
    ("commons-codec", "@external_deps//:commons_codec_commons_codec"),
    ("commons-io", "@external_deps//:commons_io_commons_io"),
    ("commons-lang3", "@external_deps//:org_apache_commons_commons_lang3"),
    ("commons-text", "@external_deps//:org_apache_commons_commons_text"),
    ("cm-autolink", "@external_deps//:org_commonmark_commonmark_ext_autolink"),
    ("gfm-strikethrough", "@external_deps//:org_commonmark_commonmark_ext_gfm_strikethrough"),
    ("gfm-tables", "@external_deps//:org_commonmark_commonmark_ext_gfm_tables"),
    ("html-types", "@external_deps//:com_google_common_html_types_types"),
    ("jsr305", "@external_deps//:com_google_code_findbugs_jsr305"),
    ("servlet-api", "@external_deps//:javax_servlet_javax_servlet_api"),
    ("gson", "@external_deps//:com_google_code_gson_gson"),
    ("guava", "@external_deps//:com_google_guava_guava"),
    ("guava-failureaccess", "@external_deps//:com_google_guava_failureaccess"),
    ("ow2-asm", "@external_deps//:org_ow2_asm_asm"),
    ("ow2-asm-analysis", "@external_deps//:org_ow2_asm_asm_analysis"),
    ("ow2-asm-commons", "@external_deps//:org_ow2_asm_asm_commons"),
    ("ow2-asm-tree", "@external_deps//:org_ow2_asm_asm_tree"),
    ("ow2-asm-util", "@external_deps//:org_ow2_asm_asm_util"),
]]

java_library(
    name = "prettify",
    visibility = ["//visibility:public"],
    exports = ["@java-prettify"],
)

java_library(
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

java_library(
    name = "jgit",
    visibility = ["//visibility:public"],
    exports = ["@jgit//org.eclipse.jgit:jgit"],
    runtime_deps = [
        ":slf4j-api",
        "@external_deps//:com_googlecode_javaewah_JavaEWAH",
    ],
)

java_library(
    name = "jgit-archive",
    visibility = ["//visibility:public"],
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

java_library(
    name = "jgit-servlet",
    visibility = ["//visibility:public"],
    exports = ["@jgit//org.eclipse.jgit.http.server:jgit-servlet"],
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

java_library(
    name = "errorprone-annotations",
    visibility = ["//visibility:public"],
    exports = ["@external_deps//:com_google_errorprone_error_prone_annotations"],
)

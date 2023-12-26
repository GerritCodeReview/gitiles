workspace(name = "gitiles")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

http_archive(
    name = "rules_python",
    sha256 = "e5470e92a18aa51830db99a4d9c492cc613761d5bdb7131c04bd92b9834380f6",
    strip_prefix = "rules_python-4b84ad270387a7c439ebdccfd530e2339601ef27",
    urls = ["https://github.com/bazelbuild/rules_python/archive/4b84ad270387a7c439ebdccfd530e2339601ef27.tar.gz"],
)

load("//tools:bazlets.bzl", "load_bazlets")

load_bazlets(
    commit = "e68cc7a45d9ee2b100024b9b12533b50a4598585",
    # local_path = "/home/<user>/projects/bazlets",
)

load(
    "@com_googlesource_gerrit_bazlets//tools:maven_jar.bzl",
    "maven_jar",
)

# JGit external repository consumed from git submodule
local_repository(
    name = "jgit",
    path = "modules/jgit",
)

# Java-Prettify external repository consumed from git submodule
local_repository(
    name = "java-prettify",
    path = "modules/java-prettify",
)

register_toolchains("//tools:error_prone_warnings_toolchain_java11_definition")

register_toolchains("//tools:error_prone_warnings_toolchain_java17_definition")

maven_jar(
    name = "error-prone-annotations",
    artifact = "com.google.errorprone:error_prone_annotations:2.22.0",
    sha1 = "bfb9e4281a4cea34f0ec85b3acd47621cfab35b4",
)

maven_jar(
    name = "commons-codec",
    artifact = "commons-codec:commons-codec:1.16.0",
    sha1 = "4e3eb3d79888d76b54e28b350915b5dc3919c9de",
)

maven_jar(
    name = "commons-lang3",
    artifact = "org.apache.commons:commons-lang3:3.8.1",
    sha1 = "6505a72a097d9270f7a9e7bf42c4238283247755",
)

maven_jar(
    name = "commons-text",
    artifact = "org.apache.commons:commons-text:1.2",
    sha1 = "74acdec7237f576c4803fff0c1008ab8a3808b2b",
)

maven_jar(
    name = "gson",
    artifact = "com.google.code.gson:gson:2.10.1",
    sha1 = "b3add478d4382b78ea20b1671390a858002feb6c",
)

maven_jar(
    name = "guava",
    artifact = "com.google.guava:guava:32.1.2-jre",
    sha1 = "5e64ec7e056456bef3a4bc4c6fdaef71e8ab6318",
)

maven_jar(
    name = "guava-failureaccess",
    artifact = "com.google.guava:failureaccess:1.0.1",
    sha1 = "1dcf1de382a0bf95a3d8b0849546c88bac1292c9",
)

maven_jar(
    name = "jsr305",
    artifact = "com.google.code.findbugs:jsr305:3.0.1",
    attach_source = False,
    sha1 = "f7be08ec23c21485b9b5a1cf1654c2ec8c58168d",
)

COMMONMARK_VERSION = "0.21.0"

# When upgrading commonmark it should also be updated in plugins/gitiles
maven_jar(
    name = "commonmark",
    artifact = "org.commonmark:commonmark:" + COMMONMARK_VERSION,
    sha1 = "c98f0473b17c87fe4fa2fc62a7c6523a2fe018f0",
)

maven_jar(
    name = "cm-autolink",
    artifact = "org.commonmark:commonmark-ext-autolink:" + COMMONMARK_VERSION,
    sha1 = "55c0312cf443fa3d5af0daeeeca00d6deee3cf90",
)

maven_jar(
    name = "autolink",
    artifact = "org.nibor.autolink:autolink:0.10.0",
    sha1 = "6579ea7079be461e5ffa99f33222a632711cc671",
)

maven_jar(
    name = "gfm-strikethrough",
    artifact = "org.commonmark:commonmark-ext-gfm-strikethrough:" + COMMONMARK_VERSION,
    sha1 = "953f4b71e133a98fcca93f3c3f4e58b895b76d1f",
)

maven_jar(
    name = "gfm-tables",
    artifact = "org.commonmark:commonmark-ext-gfm-tables:" + COMMONMARK_VERSION,
    sha1 = "fb7d65fa89a4cfcd2f51535d2549b570cf1dbd1a",
)

maven_jar(
    name = "servlet-api",
    artifact = "javax.servlet:javax.servlet-api:3.1.0",
    sha1 = "3cd63d075497751784b2fa84be59432f4905bf7c",
)

maven_jar(
    name = "truth",
    artifact = "com.google.truth:truth:1.1",
    sha1 = "6a096a16646559c24397b03f797d0c9d75ee8720",
)

# Indirect dependency of truth
maven_jar(
    name = "diffutils",
    artifact = "com.googlecode.java-diff-utils:diffutils:1.3.0",
    sha1 = "7e060dd5b19431e6d198e91ff670644372f60fbd",
)

maven_jar(
    name = "soy",
    artifact = "com.google.template:soy:2023-12-13",
    sha1 = "8b63495fba832cd93c8474a11812668876fee05c",
)

FLOGGER_VERS = "0.7.4"

maven_jar(
    name = "log4j",
    artifact = "ch.qos.reload4j:reload4j:1.2.25",
    sha1 = "45921e383a1001c2a599fc4c6cf59af80cdd1cf1",
)

maven_jar(
    name = "flogger",
    artifact = "com.google.flogger:flogger:" + FLOGGER_VERS,
    sha1 = "cec29ed8b58413c2e935d86b12d6b696dc285419",
)

maven_jar(
    name = "flogger-log4j-backend",
    artifact = "com.google.flogger:flogger-log4j-backend:" + FLOGGER_VERS,
    sha1 = "7486b1c0138647cd7714eccb8ce37b5f2ae20a76",
)

maven_jar(
    name = "flogger-google-extensions",
    artifact = "com.google.flogger:google-extensions:" + FLOGGER_VERS,
    sha1 = "c49493bd815e3842b8406e21117119d560399977",
)

maven_jar(
    name = "flogger-system-backend",
    artifact = "com.google.flogger:flogger-system-backend:" + FLOGGER_VERS,
    sha1 = "4bee7ebbd97c63ca7fb17529aeb49a57b670d061",
)

maven_jar(
    name = "html-types",
    artifact = "com.google.common.html.types:types:1.0.8",
    sha1 = "9e9cf7bc4b2a60efeb5f5581fe46d17c068e0777",
)

maven_jar(
    name = "protobuf",
    artifact = "com.google.protobuf:protobuf-java:3.19.4",
    sha1 = "748e4e0b9e4fa6b9b1fe65690aa04a9db56cfc4d",
)

maven_jar(
    name = "icu4j",
    artifact = "com.ibm.icu:icu4j:74.2",
    sha1 = "97222d018f7f43cae88cacd1fad39717b001ffc4",
)

maven_jar(
    name = "javaewah",
    artifact = "com.googlecode.javaewah:JavaEWAH:1.2.3",
    sha1 = "13a27c856e0c8808cee9a64032c58eee11c3adc9",
)

# When upgrading commons_compress, upgrade tukaani_xz to the
# corresponding version
maven_jar(
    name = "commons-compress",
    artifact = "org.apache.commons:commons-compress:1.25.0",
    sha1 = "9d35aec423da6c8a7f93d7e9e1c6b1d9fe14bb5e",
)

# Transitive dependency of commons_compress. Should only be
# upgraded at the same time as commons_compress.
maven_jar(
    name = "tukaani-xz",
    artifact = "org.tukaani:xz:1.9",
    attach_source = False,
    sha1 = "1ea4bec1a921180164852c65006d928617bd2caf",
)

maven_jar(
    name = "junit",
    artifact = "junit:junit:4.12",
    sha1 = "2973d150c0dc1fefe998f834810d68f278ea58ec",
)

maven_jar(
    name = "hamcrest",
    artifact = "org.hamcrest:hamcrest:2.2",
    sha1 = "1820c0968dba3a11a1b30669bb1f01978a91dedc",
)

maven_jar(
    name = "mockito",
    artifact = "org.mockito:mockito-core:5.6.0",
    sha1 = "550b7a0eb22e1d72d33dcc2e5ef6954f73100d76",
)

BYTE_BUDDY_VERSION = "1.14.9"

maven_jar(
    name = "bytebuddy",
    artifact = "net.bytebuddy:byte-buddy:" + BYTE_BUDDY_VERSION,
    sha1 = "b69e7fff6c473d3ed2b489cdfd673a091fd94226",
)

maven_jar(
    name = "bytebuddy-agent",
    artifact = "net.bytebuddy:byte-buddy-agent:" + BYTE_BUDDY_VERSION,
    sha1 = "dfb8707031008535048bad2b69735f46d0b6c5e5",
)

maven_jar(
    name = "objenesis",
    artifact = "org.objenesis:objenesis:2.6",
    sha1 = "639033469776fd37c08358c6b92a4761feb2af4b",
)

SL_VERS = "1.7.36"

maven_jar(
    name = "log-api",
    artifact = "org.slf4j:slf4j-api:" + SL_VERS,
    sha1 = "6c62681a2f655b49963a5983b8b0950a6120ae14",
)

maven_jar(
    name = "slf4j-simple",
    artifact = "org.slf4j:slf4j-simple:" + SL_VERS,
    sha1 = "a41f9cfe6faafb2eb83a1c7dd2d0dfd844e2a936",
)

JETTY_VERSION = "9.4.49.v20220914"

maven_jar(
    name = "servlet",
    artifact = "org.eclipse.jetty:jetty-servlet:" + JETTY_VERSION,
    sha1 = "53ca0898f02e72b6830551031ee0062430134a05",
)

maven_jar(
    name = "security",
    artifact = "org.eclipse.jetty:jetty-security:" + JETTY_VERSION,
    sha1 = "057a67eeb12078b620131664b3b7a37ea4c5aefe",
)

maven_jar(
    name = "server",
    artifact = "org.eclipse.jetty:jetty-server:" + JETTY_VERSION,
    sha1 = "502f99eed028139e71a4afebefa291ace12b9c1c",
)

maven_jar(
    name = "continuation",
    artifact = "org.eclipse.jetty:jetty-continuation:" + JETTY_VERSION,
    sha1 = "0df64c20caeba57b681abc252bffd51d19f5be70",
)

maven_jar(
    name = "http",
    artifact = "org.eclipse.jetty:jetty-http:" + JETTY_VERSION,
    sha1 = "ef1e3bde212115eb4bb0740aaf79029b624d4e30",
)

maven_jar(
    name = "io",
    artifact = "org.eclipse.jetty:jetty-io:" + JETTY_VERSION,
    sha1 = "cb33d9a3bdb6e2173b9b9cfc94c0b45f9a21a1af",
)

maven_jar(
    name = "util",
    artifact = "org.eclipse.jetty:jetty-util:" + JETTY_VERSION,
    sha1 = "29008dbc6dfac553d209f54193b505d73c253a41",
)

OW2_VERS = "9.2"

maven_jar(
    name = "ow2-asm",
    artifact = "org.ow2.asm:asm:" + OW2_VERS,
    sha1 = "81a03f76019c67362299c40e0ba13405f5467bff",
)

maven_jar(
    name = "ow2-asm-analysis",
    artifact = "org.ow2.asm:asm-analysis:" + OW2_VERS,
    sha1 = "7487dd756daf96cab9986e44b9d7bcb796a61c10",
)

maven_jar(
    name = "ow2-asm-commons",
    artifact = "org.ow2.asm:asm-commons:" + OW2_VERS,
    sha1 = "f4d7f0fc9054386f2893b602454d48e07d4fbead",
)

maven_jar(
    name = "ow2-asm-tree",
    artifact = "org.ow2.asm:asm-tree:" + OW2_VERS,
    sha1 = "d96c99a30f5e1a19b0e609dbb19a44d8518ac01e",
)

maven_jar(
    name = "ow2-asm-util",
    artifact = "org.ow2.asm:asm-util:" + OW2_VERS,
    sha1 = "fbc178fc5ba3dab50fd7e8a5317b8b647c8e8946",
)

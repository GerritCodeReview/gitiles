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
    commit = "de7597593918677465f8ef4330a62f0b9a50f81c",
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

register_toolchains("//tools:error_prone_warnings_toolchain_java17_definition")

register_toolchains("//tools:error_prone_warnings_toolchain_java21_definition")

maven_jar(
    name = "error-prone-annotations",
    artifact = "com.google.errorprone:error_prone_annotations:2.46.0",
    sha1 = "4ecb5d2392c38c46e6cb65e1bf60be708d97005d",
)

maven_jar(
    name = "commons-codec",
    artifact = "commons-codec:commons-codec:1.18.0",
    sha1 = "ee45d1cf6ec2cc2b809ff04b4dc7aec858e0df8f",
)

maven_jar(
    name = "commons-io",
    artifact = "commons-io:commons-io:2.21.0",
    sha1 = "52a6f68fe5afe335cde95461dd5c3412f04996f7",
)

maven_jar(
    name = "commons-lang3",
    artifact = "org.apache.commons:commons-lang3:3.18.0",
    sha1 = "fb14946f0e39748a6571de0635acbe44e7885491",
)

maven_jar(
    name = "commons-text",
    artifact = "org.apache.commons:commons-text:1.10.0",
    sha1 = "3363381aef8cef2dbc1023b3e3a9433b08b64e01",
)

maven_jar(
    name = "gson",
    artifact = "com.google.code.gson:gson:2.13.2",
    sha1 = "48b8230771e573b54ce6e867a9001e75977fe78e",
)

maven_jar(
    name = "guava",
    artifact = "com.google.guava:guava:33.5.0-jre",
    sha1 = "8699de25f2f979108d6c1b804a7ba38cda1116bc",
)

maven_jar(
    name = "guava-failureaccess",
    artifact = "com.google.guava:failureaccess:1.0.3",
    sha1 = "aeaffd00d57023a2c947393ed251f0354f0985fc",
)

maven_jar(
    name = "jsr305",
    artifact = "com.google.code.findbugs:jsr305:3.0.1",
    attach_source = False,
    sha1 = "f7be08ec23c21485b9b5a1cf1654c2ec8c58168d",
)

COMMONMARK_VERSION = "0.24.0"

# When upgrading commonmark it should also be updated in plugins/gitiles
maven_jar(
    name = "commonmark",
    artifact = "org.commonmark:commonmark:" + COMMONMARK_VERSION,
    sha1 = "59af01016ece382b55b4acb6a5190b08879c637c",
)

maven_jar(
    name = "cm-autolink",
    artifact = "org.commonmark:commonmark-ext-autolink:" + COMMONMARK_VERSION,
    sha1 = "703e28852088ff1b4b3a06622416fd807147bd84",
)

maven_jar(
    name = "autolink",
    artifact = "org.nibor.autolink:autolink:0.11.0",
    sha1 = "32abc7854d5801d19ff16be92362fa4c511d9a70",
)

maven_jar(
    name = "gfm-strikethrough",
    artifact = "org.commonmark:commonmark-ext-gfm-strikethrough:" + COMMONMARK_VERSION,
    sha1 = "9e9c1e5b50340643099d52c6b841f60fb6f54c27",
)

maven_jar(
    name = "gfm-tables",
    artifact = "org.commonmark:commonmark-ext-gfm-tables:" + COMMONMARK_VERSION,
    sha1 = "8a30c4e89ce33450c47604325751bec613bce541",
)

maven_jar(
    name = "servlet-api",
    artifact = "javax.servlet:javax.servlet-api:4.0.1",
    sha1 = "a27082684a2ff0bf397666c3943496c44541d1ca",
)

maven_jar(
    name = "truth",
    artifact = "com.google.truth:truth:1.4.4",
    sha1 = "33810058273a2a3b6ce6d1f8c8621bfc85493f67",
)

# Indirect dependency of truth
maven_jar(
    name = "diffutils",
    artifact = "io.github.java-diff-utils:java-diff-utils:4.16",
    sha1 = "cca1e7dc2460d0afeebc3fc4a3386eadede08c5a",
)

maven_jar(
    name = "soy",
    artifact = "com.google.template:soy:2024-01-30",
    sha1 = "6e9ccb00926325c7a9293ed05a2eaf56ea15d60e",
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
    artifact = "com.google.protobuf:protobuf-java:4.33.2",
    sha1 = "c85bf5de1ad10453792675f6515401f7b8eb6860",
)

maven_jar(
    name = "icu4j",
    artifact = "com.ibm.icu:icu4j:78.2",
    sha1 = "31b9d9a35d283432d0ce1a8b6e2631dcfd046ab8",
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
    artifact = "org.apache.commons:commons-compress:1.28.0",
    sha1 = "e482f2c7a88dac3c497e96aa420b6a769f59c8d7",
)

# Transitive dependency of commons_compress. Should only be
# upgraded at the same time as commons_compress.
maven_jar(
    name = "tukaani-xz",
    artifact = "org.tukaani:xz:1.11",
    attach_source = False,
    sha1 = "bdfd1774efb216f506f4f3c5b08c205b308c50aa",
)

maven_jar(
    name = "junit",
    artifact = "junit:junit:4.13.2",
    sha1 = "8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12",
)

maven_jar(
    name = "hamcrest",
    artifact = "org.hamcrest:hamcrest:3.0",
    sha1 = "8fd9b78a8e6a6510a078a9e30e9e86a6035cfaf7",
)

maven_jar(
    name = "mockito",
    artifact = "org.mockito:mockito-core:5.21.0",
    sha1 = "121287b8287464a5a7af2e47d5dbc49ca38a892f",
)

BYTE_BUDDY_VERSION = "1.18.2"

maven_jar(
    name = "bytebuddy",
    artifact = "net.bytebuddy:byte-buddy:" + BYTE_BUDDY_VERSION,
    sha1 = "7ac991b4bd502e2567efcdecc0d2e9b3f7dd3859",
)

maven_jar(
    name = "bytebuddy-agent",
    artifact = "net.bytebuddy:byte-buddy-agent:" + BYTE_BUDDY_VERSION,
    sha1 = "62f38a6faf7f069d661b79a07d566f504b0b20c4",
)

maven_jar(
    name = "objenesis",
    artifact = "org.objenesis:objenesis:3.0.1",
    sha1 = "11cfac598df9dc48bb9ed9357ed04212694b7808",
)

SL_VERS = "2.0.17"

maven_jar(
    name = "log-api",
    artifact = "org.slf4j:slf4j-api:" + SL_VERS,
    sha1 = "d9e58ac9c7779ba3bf8142aff6c830617a7fe60f",
)

maven_jar(
    name = "slf4j-simple",
    artifact = "org.slf4j:slf4j-simple:" + SL_VERS,
    sha1 = "9872a3fd794ffe7b18d17747926a64d61526ca96",
)

JETTY_VERSION = "9.4.57.v20241219"

maven_jar(
    name = "servlet",
    artifact = "org.eclipse.jetty:jetty-servlet:" + JETTY_VERSION,
    sha1 = "3e648eebddbf5ad0c0f7698e50c6a69c4a77fd95",
)

maven_jar(
    name = "security",
    artifact = "org.eclipse.jetty:jetty-security:" + JETTY_VERSION,
    sha1 = "2b545f68d45b947fdc6e279a0e8ae3630ec10e05",
)

maven_jar(
    name = "server",
    artifact = "org.eclipse.jetty:jetty-server:" + JETTY_VERSION,
    sha1 = "ad3baf52b98b4a32f5714fe2e58ac0e502b4e4d8",
)

maven_jar(
    name = "continuation",
    artifact = "org.eclipse.jetty:jetty-continuation:" + JETTY_VERSION,
    sha1 = "c2bf5c810049fe23945f737a3c4743da81baa62d",
)

maven_jar(
    name = "http",
    artifact = "org.eclipse.jetty:jetty-http:" + JETTY_VERSION,
    sha1 = "c7a3a9c599346708894cf355e03105937f45f427",
)

maven_jar(
    name = "io",
    artifact = "org.eclipse.jetty:jetty-io:" + JETTY_VERSION,
    sha1 = "bd0ca6e5c4314972cd91f427fa09dedfe3b84ff5",
)

maven_jar(
    name = "util",
    artifact = "org.eclipse.jetty:jetty-util:" + JETTY_VERSION,
    sha1 = "7bf7ea75644ac064199e1e32c66ccd312239f2dc",
)

OW2_VERS = "9.9.1"

maven_jar(
    name = "ow2-asm",
    artifact = "org.ow2.asm:asm:" + OW2_VERS,
    sha1 = "2ceea6ab43bcae1979b2a6d85fc0ca429877e5ab",
)

maven_jar(
    name = "ow2-asm-analysis",
    artifact = "org.ow2.asm:asm-analysis:" + OW2_VERS,
    sha1 = "1ab8d9316ef7a67240087919a708246c37ed1660",
)

maven_jar(
    name = "ow2-asm-commons",
    artifact = "org.ow2.asm:asm-commons:" + OW2_VERS,
    sha1 = "ab35de4c537184a09339069f1a3b3aacf2289149",
)

maven_jar(
    name = "ow2-asm-tree",
    artifact = "org.ow2.asm:asm-tree:" + OW2_VERS,
    sha1 = "b6b1b3366296163b4b1f540731aad0a2baa484d8",
)

maven_jar(
    name = "ow2-asm-util",
    artifact = "org.ow2.asm:asm-util:" + OW2_VERS,
    sha1 = "e51f5b0ae0b0c1960687ae970a2a3434d39d8abb",
)

# When upgrading mermaid, upgrade com.google.gitiles.doc.MermaidExtension#MJS_PATH to the
# corresponding version
maven_jar(
    name = "mermaid",
    artifact = "org.webjars.npm:mermaid:11.12.3",
    attach_source = False,
    sha1 = "0a9e1c0c243151c56c46e44d9ae532302d1c15df",
)

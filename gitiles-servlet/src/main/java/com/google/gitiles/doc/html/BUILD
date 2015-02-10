licenses(["notice"])  # Apache 2.0

package_group(
    name = "src",
    packages = [
        "//third_party/java_src/gitiles/...",
    ],
)

package(
    default_visibility = [":src"],
)

java_library(
    name = "html",
    srcs = glob(["*.java"]),
    deps = [
        "//java/com/google/common/base",
        "//third_party/java/apache_commons_lang3",
        "//third_party/java/pegdown",
        "//third_party/java_src/gitiles:doc",
        "//third_party/java_src/soy/java/com/google/template/soy/data",
        "//third_party/java_src/soy/java/com/google/template/soy/data:unsafesanitizedcontentordainer",
        "//third_party/java_src/soy/java/com/google/template/soy/shared:restricted",
    ],
)

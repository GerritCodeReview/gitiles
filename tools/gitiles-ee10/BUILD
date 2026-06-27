load("@rules_shell//shell:sh_test.bzl", "sh_test")

# The transform rule, the junit macro, and this generated-sources checker are
# provided by bazlets (@com_googlesource_gerrit_bazlets//tools). Gitiles selects
# the to_jakarta direction (javax -> jakarta) and carries no renames data; this
# package only wires the check for the production servlet library.
sh_test(
    name = "generated_srcs_test",
    srcs = ["@com_googlesource_gerrit_bazlets//tools:generated_srcs_test.sh"],
    args = [
        "--forbid",
        "javax\\.servlet",
        "--forbid",
        "com\\.google\\.gitiles\\..*\\.ee10",
        "--require-if-source",
        "javax\\.servlet",
        "jakarta\\.servlet",
        "--module",
        "java/",
        "$(location //java/com/google/gitiles:servlet-ee10-srcs)",
        "$(locations //java/com/google/gitiles:srcs)",
        "--module",
        "javatests/",
        "$(location //javatests/com/google/gitiles:testutil-ee10-srcs)",
        "$(locations //javatests/com/google/gitiles:testutil-srcs)",
        "--module",
        "javatests/",
        "$(location //javatests/com/google/gitiles:test-ee10-srcs)",
        "$(locations //javatests/com/google/gitiles:test-srcs)",
        "--module",
        "java/",
        "$(location //java/com/google/gitiles/dev:devserver-base-ee10-srcs)",
        "$(location //java/com/google/gitiles/dev:DevServerBase.java)",
    ],
    data = [
        "//java/com/google/gitiles:servlet-ee10-srcs",
        "//java/com/google/gitiles:srcs",
        "//java/com/google/gitiles/dev:DevServerBase.java",
        "//java/com/google/gitiles/dev:devserver-base-ee10-srcs",
        "//javatests/com/google/gitiles:test-ee10-srcs",
        "//javatests/com/google/gitiles:test-srcs",
        "//javatests/com/google/gitiles:testutil-ee10-srcs",
        "//javatests/com/google/gitiles:testutil-srcs",
    ],
)

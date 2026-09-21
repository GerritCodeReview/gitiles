load("//tools:js_test.bzl", "js_test")

js_test(
    name = "file_search_test",
    srcs = ["file_search_test.js"],
    data = [
        "fake_dom.js",
        "//resources/com/google/gitiles:gitiles",
    ],
)

js_test(
    name = "file_search_worker_test",
    srcs = ["file_search_worker_test.js"],
    data = ["//resources/com/google/gitiles:gitiles"],
)

js_test(
    name = "js_test_rule_test",
    srcs = ["js_test_rule_test.js"],
    data = ["//resources/com/google/gitiles:gitiles"],
)

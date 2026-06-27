# EE10 (jakarta.servlet) hand-written overrides of the canonical test fakes.
# These keep the same FQDN (package com.google.gitiles) as the canonical EE8
# fakes; only one flavour is ever on a classpath at a time. They are consumed by
# //javatests/com/google/gitiles:testutil-ee10 in place of the (excluded-from-
# transform) canonical FakeHttpServletRequest/Response.
filegroup(
    name = "fakes",
    testonly = 1,
    srcs = [
        "FakeHttpServletRequest.java",
        "FakeHttpServletResponse.java",
    ],
    visibility = ["//javatests/com/google/gitiles:__pkg__"],
)

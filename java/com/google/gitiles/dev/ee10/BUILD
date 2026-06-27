# EE10 (jakarta.servlet) hand-written overlay of the canonical EE8 DevServer.
# Keeps the same FQDN (package com.google.gitiles.dev) as the canonical EE8
# DevServer; only one flavour is ever on a classpath at a time. Consumed by
# //java/com/google/gitiles/dev:lib-ee10 in place of the (EE8) DevServer.java,
# alongside the transform-generated DevServerBase. Having a BUILD here also keeps
# this file out of the canonical //java/com/google/gitiles/dev glob.
filegroup(
    name = "overlay",
    srcs = ["DevServer.java"],
    visibility = ["//java/com/google/gitiles/dev:__pkg__"],
)

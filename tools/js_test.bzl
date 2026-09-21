"""A test rule for plain JavaScript, using Node's built-in test runner.

Gitiles already depends on `rules_nodejs`, whose module registers a hermetic
Node toolchain for every supported platform, so this rule needs no new
dependency and no `MODULE.bazel` change. `rules_nodejs` 6.x ships toolchains
only -- `nodejs_test` lives in `aspect_rules_js` -- hence the rule here.

Tests are written against `node:test` and `node:assert`, which are part of the
runtime. There is no package manager, no lockfile and no `node_modules`.
"""

# Bazel passes XML_OUTPUT_FILE when it wants a JUnit report; without it a test
# failure is just a red target with a log to go read.
_LAUNCHER = """#!/bin/sh
set -eu
if [ -n "${{XML_OUTPUT_FILE:-}}" ]; then
  exec ./{node} --test --test-reporter=junit \\
    --test-reporter-destination="$XML_OUTPUT_FILE" \\
    --test-reporter=spec --test-reporter-destination=stdout {tests}
fi
exec ./{node} --test {tests}
"""

def _js_test_impl(ctx):
    node = ctx.toolchains["@rules_nodejs//nodejs:toolchain_type"].nodeinfo.node
    launcher = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        output = launcher,
        is_executable = True,
        content = _LAUNCHER.format(
            node = node.short_path,
            tests = " ".join([f.short_path for f in ctx.files.srcs]),
        ),
    )
    return [DefaultInfo(
        executable = launcher,
        runfiles = ctx.runfiles(files = ctx.files.srcs + ctx.files.data + [node]),
    )]

js_test = rule(
    doc = "Runs `node --test` over `srcs` with `data` available in runfiles.",
    implementation = _js_test_impl,
    test = True,
    attrs = {
        "srcs": attr.label_list(
            doc = "Test files, passed to `node --test`.",
            allow_files = [".js"],
            mandatory = True,
        ),
        "data": attr.label_list(
            doc = "Files the tests read at run time, such as the code under test.",
            allow_files = True,
        ),
    },
    toolchains = ["@rules_nodejs//nodejs:toolchain_type"],
)

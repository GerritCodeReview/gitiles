// Tests the js_test rule's own contract.
//
// The rule is build glue, and build glue that nothing exercises is build glue
// nobody knows is broken. Two promises are worth pinning, because both are
// environment-dependent and neither is visible in the source of a test that
// merely passes:
//
//   1. A hermetic Node resolves from the toolchain rules_nodejs registers, on
//      whatever machine the build runs on. Nothing is downloaded by hand and
//      no system node is consulted.
//   2. A file listed in `data` is readable at its workspace-relative path,
//      even when it comes from another package.
//
// Without (1) the lane silently falls back to nothing at all on CI; without
// (2) every test that reads the code under test fails in the same confusing
// way.

const {test} = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

test('runs on the toolchain Node, not a node found on PATH', () => {
  // execPath is the resolved binary. The runfiles entry for the toolchain
  // Node is a symlink to wherever Bazel fetched it, which is outside the
  // runfiles tree and varies with cache configuration, so its location
  // cannot be pinned portably. What can be pinned is that it is not
  // whatever a bare `node` would resolve to. Where no system node exists, a
  // launcher that fell back to one would not start at all.
  const running = fs.realpathSync(process.execPath);
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    const candidate = path.resolve(dir, 'node');
    if (fs.existsSync(candidate)) {
      assert.notStrictEqual(fs.realpathSync(candidate), running,
          `running the system node at ${candidate}`);
    }
  }
});

test('can read a data dependency from another package', () => {
  const css = fs.readFileSync('resources/com/google/gitiles/static/base.css', 'utf8');
  assert.ok(css.length === 0);
});

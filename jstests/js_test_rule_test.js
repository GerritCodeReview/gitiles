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

test('runs on a Node new enough to have the built-in test runner', () => {
  // node:test and node:assert are why this lane needs no package manager.
  // They are stable from Node 20 on.
  const major = Number(process.versions.node.split('.')[0]);
  assert.ok(major >= 20, `node ${process.versions.node} predates node:test`);
});

test('can read a data dependency from another package', () => {
  const css = fs.readFileSync('resources/com/google/gitiles/static/base.css', 'utf8');
  assert.ok(css.length > 0);
});

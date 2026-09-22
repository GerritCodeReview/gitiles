// Tests for the file finder's search worker.
//
// The worker is a script, not a module: it exports nothing and assigns
// `self.onmessage`. That message protocol is the seam these tests use, so the
// code under test needs no modification and is exercised through exactly the
// surface a browser drives it through.

const {test, describe} = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const vm = require('node:vm');

const SRC = 'resources/com/google/gitiles/static/file-search-worker.js';

/** The worker's display cap; see LIMIT in the source. */
const LIMIT = 20;

/**
 * Evaluates the worker in a fresh global containing only what a real
 * DedicatedWorkerGlobalScope would offer it, and returns a handle for talking
 * to it.
 */
function loadWorker(fetchImpl) {
  const outbox = [];
  const sandbox = {
    TextEncoder,
    TextDecoder,
    fetch: fetchImpl,
    self: {
      onmessage: null,
      // A real postMessage structured-clones across the worker boundary, so
      // the main thread never sees the worker's own objects. Modelling that is
      // both faithful and necessary: values built inside a vm context carry
      // that context's prototypes, and assert.deepStrictEqual compares
      // prototypes.
      postMessage: (m) => outbox.push(JSON.parse(JSON.stringify(m))),
    },
  };
  vm.runInNewContext(fs.readFileSync(SRC, 'utf8'), sandbox, {filename: SRC});
  return {
    outbox,
    /** Delivers one message and returns the reply, or undefined if silent. */
    async send(msg) {
      const before = outbox.length;
      await sandbox.self.onmessage({data: msg});
      return outbox.length > before ? outbox[outbox.length - 1] : undefined;
    },
  };
}

/** Serves `paths` as the listing endpoint would, guard prefix and all. */
function serving(paths) {
  return async () => ({
    ok: true,
    status: 200,
    text: async () => ")]}'\n" + JSON.stringify({paths}),
  });
}

/**
 * Builds a worker holding an index over `paths`.
 *
 * The paths must be in byte order, as a recursive git tree listing is: the
 * index binary searches them.
 */
async function indexed(paths) {
  const w = loadWorker(serving(paths));
  const r = await w.send({type: 'load', url: '/+/HEAD/?format=JSON'});
  assert.strictEqual(r.type, 'ready', `load failed: ${JSON.stringify(r)}`);
  assert.strictEqual(r.total, paths.length);
  return w;
}

/** Types a query one keystroke at a time, the way a reader produces it. */
async function type(w, q) {
  let last;
  for (let k = 1; k <= q.length; k++) {
    last = await w.send({type: 'query', seq: k, q: q.slice(0, k)});
  }
  return last;
}

const CORPUS = [
  'base/base64.h',
  'base/logging.h',
  'chrome/test/data/dromaeo/tests/dromaeo-string-base64.html',
  'net/base/base64.h',
  'third_party/blink/web_tests/tables/bug3166-4.html',
];

describe('loading', () => {
  test('strips the anti-XSSI prefix from the listing', async () => {
    const w = loadWorker(serving(CORPUS));
    const r = await w.send({type: 'load', url: '/+/HEAD/?format=JSON'});
    assert.deepStrictEqual(r, {type: 'ready', total: CORPUS.length});
  });

  test('reports a failed fetch instead of throwing', async () => {
    const w = loadWorker(async () => ({ok: false, status: 503}));
    const r = await w.send({type: 'load', url: '/+/HEAD/?format=JSON'});
    assert.strictEqual(r.type, 'error');
    assert.match(r.message, /503/);
  });

  test('ignores a query that arrives before the index', async () => {
    const w = loadWorker(serving(CORPUS));
    assert.strictEqual(await w.send({type: 'query', seq: 1, q: 'base'}), undefined);
    assert.strictEqual(await w.send({type: 'recent', seq: 2, paths: []}), undefined);
  });
});

describe('ranking', () => {
  test('a basename carrying the whole query outranks an incidental match', async () => {
    // The directory "base" can consume "base" out of the query, which would
    // score base/base64.h as a directory-assisted match and sink it below
    // files whose directories contributed nothing.
    const w = await indexed(CORPUS);
    const r = await type(w, 'base64.h');
    assert.deepStrictEqual(r.items.slice(0, 2).map((i) => i.path), [
      'base/base64.h',
      'net/base/base64.h',
    ]);
  });

  test('a shallower path breaks a tie', async () => {
    const w = await indexed(CORPUS);
    const r = await type(w, 'logging.h');
    assert.strictEqual(r.items[0].path, 'base/logging.h');
  });

  test('echoes the sequence number so the UI can discard stale replies', async () => {
    const w = await indexed(CORPUS);
    const r = await w.send({type: 'query', seq: 7, q: 'base64'});
    assert.strictEqual(r.seq, 7);
    assert.strictEqual(r.q, 'base64');
  });
});

describe('empty query', () => {
  test('offers a capped, exact sample of the tree', async () => {
    const paths = [];
    for (let i = 0; i < 30; i++) paths.push(`dir/f${String(i).padStart(3, '0')}.txt`);
    const w = await indexed(paths);
    const r = await w.send({type: 'query', seq: 1, q: ''});
    assert.strictEqual(r.total, paths.length);
    assert.strictEqual(r.exact, true);
    assert.strictEqual(r.items.length, LIMIT);
    assert.deepStrictEqual(r.items[0].ranges, []);
  });
});

describe('path-prefix matches', () => {
  test('are promoted and marked across the whole query', async () => {
    const w = await indexed(CORPUS);
    const r = await type(w, 'base/base64');
    assert.strictEqual(r.items[0].path, 'base/base64.h');
    assert.deepStrictEqual(r.items[0].ranges, [[0, 'base/base64'.length]]);
  });

  test('report an inexact total once they fill the display', async () => {
    // The count is a lower bound: other paths may match without starting with
    // the query, so the display has to say "+".
    const paths = [];
    for (let i = 0; i < LIMIT + 5; i++) {
      paths.push(`pkg/f${String(i).padStart(3, '0')}.txt`);
    }
    const w = await indexed(paths);
    const r = await type(w, 'pkg/');
    assert.strictEqual(r.total, paths.length);
    assert.strictEqual(r.exact, false);
    assert.strictEqual(r.items.length, LIMIT);
  });
});

describe('highlight ranges', () => {
  test('are omitted for non-ASCII paths', async () => {
    // Ranges are UTF-8 byte offsets and the UI applies them as UTF-16 string
    // indices. They agree only for ASCII; elsewhere, no marks beat wrong ones.
    const paths = ['dir/plain.txt', 'dir/\u2605\u661f\u2605.txt'];

    const w = await indexed(paths);
    const ascii = await type(w, 'plain');
    assert.deepStrictEqual(ascii.items[0].ranges, [[4, 5]]);

    const w2 = await indexed(paths);
    const star = await type(w2, '\u2605\u661f\u2605');
    assert.strictEqual(star.items[0].path, 'dir/\u2605\u661f\u2605.txt');
    assert.deepStrictEqual(star.items[0].ranges, []);
  });
});

describe('remembered files', () => {
  test('are cut to the number of rows the UI paints', async () => {
    // Otherwise a reader could arrow past the last painted row into a
    // selection that highlighted nothing and did nothing on Enter.
    const paths = [];
    for (let i = 0; i < 40; i++) paths.push(`dir/f${String(i).padStart(3, '0')}.txt`);
    const w = await indexed(paths);
    const r = await w.send({type: 'recent', seq: 1, paths: paths.slice()});
    assert.strictEqual(r.items.length, LIMIT);
    assert.strictEqual(r.recent, true);
    assert.strictEqual(r.q, '');
  });

  test('drop paths that no longer exist at this revision', async () => {
    const w = await indexed(['dir/kept.txt']);
    const r = await w.send({
      type: 'recent',
      seq: 1,
      paths: ['dir/kept.txt', 'dir/deleted.txt'],
    });
    assert.deepStrictEqual(r.items.map((i) => i.path), ['dir/kept.txt']);
  });

  test('keep the caller\'s order, since the scores live in the UI', async () => {
    const paths = ['dir/a.txt', 'dir/b.txt', 'dir/c.txt'];
    const w = await indexed(paths);
    const r = await w.send({
      type: 'recent',
      seq: 1,
      paths: ['dir/c.txt', 'dir/a.txt', 'dir/b.txt'],
    });
    assert.deepStrictEqual(r.items.map((i) => i.path), [
      'dir/c.txt',
      'dir/a.txt',
      'dir/b.txt',
    ]);
  });
});

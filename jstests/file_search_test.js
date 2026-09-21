// Tests for the file finder's UI layer.
//
// file-search.js exports nothing, so these drive it the way a reader does:
// through the document and the events the browser would deliver. See
// fake_dom.js for what the stand-in models and, more importantly, what it
// does not.

const {test, describe} = require('node:test');
const assert = require('node:assert');
const {load} = require('./fake_dom.js');

/** The number of rows the palette paints; see LIMIT in file-search.js. */
const LIMIT = 20;

/** Opens the palette and brings its worker to the ready state. */
function opened(options) {
  const env = load(options);
  env.globalKey('/');
  env.worker.reply({type: 'ready', total: 1234});
  return env;
}

/** The last message the page sent the worker. */
function lastSent(env) {
  return env.worker.sent[env.worker.sent.length - 1];
}

/** Answers the outstanding query with `paths`, echoing its sequence number. */
function respond(env, paths, opts = {}) {
  const msg = lastSent(env);
  env.worker.reply({
    type: 'results',
    seq: opts.seq !== undefined ? opts.seq : msg.seq,
    q: msg.q || '',
    total: opts.total !== undefined ? opts.total : paths.length,
    exact: opts.exact !== undefined ? opts.exact : true,
    recent: !!opts.recent,
    items: paths.map((p) => ({path: p, ranges: opts.ranges || []})),
  });
}

function paths(n, prefix = 'dir/f') {
  const out = [];
  for (let i = 0; i < n; i++) out.push(`${prefix}${String(i).padStart(3, '0')}.txt`);
  return out;
}

describe('opening', () => {
  test('"/" reveals the palette and puts the caret in the box', () => {
    const env = load();
    assert.strictEqual(env.el.root.hidden, true);
    const ev = env.globalKey('/');
    assert.strictEqual(ev.preventDefaultCount, 1, 'Firefox binds / to Quick Find');
    assert.strictEqual(env.el.root.hidden, false);
    assert.strictEqual(env.doc.activeElement, env.el.input);
    assert.ok(env.doc.body.classList.contains('FileSearch-open'));
  });

  test('"/" is left alone while the reader is typing somewhere else', () => {
    const env = load();
    const box = env.doc.createElement('input');
    env.doc.body.appendChild(box);
    box.focus();
    env.globalKey('/');
    assert.strictEqual(env.el.root.hidden, true);
  });

  test('the index is fetched on first open, not on page load', () => {
    const env = load();
    assert.strictEqual(env.workers.length, 0, 'a reader who never searches costs nothing');
    env.globalKey('/');
    assert.strictEqual(env.workers.length, 1);
    assert.deepStrictEqual(lastSent(env), {
      type: 'load',
      url: '/repo/+/HEAD/?format=JSON&paths_only=1',
    });
  });

  test('Escape closes and gives focus back', () => {
    const env = load();
    const before = env.doc.createElement('button');
    env.doc.body.appendChild(before);
    before.focus();
    env.globalKey('/');
    const ev = env.key('Escape');
    assert.strictEqual(ev.preventDefaultCount, 1);
    assert.strictEqual(env.el.root.hidden, true);
    assert.strictEqual(env.doc.activeElement, before);
  });
});

describe('rows', () => {
  test('are identified and taken out of the tab order', () => {
    // Arrow keys never move focus, so the input has to name the selected row
    // for a screen reader; and a listbox option that is a tab stop would let
    // Tab walk out of a dialog that calls itself modal.
    const env = load();
    const lis = env.el.list.children;
    assert.strictEqual(lis.length, LIMIT);
    lis.forEach((li, i) => {
      assert.strictEqual(li.id, 'file-search-row-' + i);
      assert.strictEqual(li.getAttribute('role'), 'option');
      assert.strictEqual(li.children[0].tabIndex, -1);
    });
  });

  test('link to the listing base with separators preserved', () => {
    const env = opened();
    env.type('a b');
    respond(env, ['dir with space/a+b.txt']);
    const a = env.el.list.children[0].children[0];
    assert.strictEqual(a.href, '/repo/+/HEAD/dir%20with%20space/a%2Bb.txt');
  });

  test('mark the matched characters and nothing else', () => {
    const env = opened();
    env.type('plain');
    respond(env, ['dir/plain.txt'], {ranges: [[4, 5]]});
    const a = env.el.list.children[0].children[0];
    const marks = a.children.filter((c) => c.tagName === 'MARK');
    assert.strictEqual(marks.length, 1);
    assert.strictEqual(marks[0].textContent, 'plain');
    assert.strictEqual(a.textContent, 'dir/plain.txt');
  });
});

describe('selection', () => {
  test('returns to the best match on every new query', () => {
    // Carrying it over meant that once a reader had arrowed at all, every
    // later query left them on an arbitrary row -- the row Enter opens.
    const env = opened();
    env.type('f');
    respond(env, paths(5));
    env.key('ArrowDown');
    env.key('ArrowDown');
    assert.strictEqual(env.activeDescendant(), 'file-search-row-2');

    env.type('fo');
    respond(env, paths(5));
    assert.strictEqual(env.activeDescendant(), 'file-search-row-0');
  });

  test('cannot escape the rows that were painted', () => {
    // The display is what the selection moves over. Sizing it from the item
    // count instead let a longer list strand the selection past the last row,
    // where it highlighted nothing and Enter did nothing.
    const env = opened();
    env.type('f');
    respond(env, paths(LIMIT + 5));
    for (let i = 0; i < LIMIT + 10; i++) env.key('ArrowDown');
    assert.strictEqual(env.activeDescendant(), 'file-search-row-' + (LIMIT - 1));

    env.key('Enter');
    assert.deepStrictEqual(env.navigations, ['/repo/+/HEAD/dir/f019.txt']);
  });

  test('is named for a screen reader, and unnamed when there is nothing', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(3));
    assert.strictEqual(env.activeDescendant(), 'file-search-row-0');
    respond(env, []);
    assert.strictEqual(env.activeDescendant(), null);
  });

  test('Tab is swallowed so focus cannot leave the modal', () => {
    const env = opened();
    const ev = env.key('Tab');
    assert.strictEqual(ev.preventDefaultCount, 1);
  });

  test('PageDown moves by half a screen and stops at the end', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(15));
    env.key('PageDown');
    assert.strictEqual(env.activeDescendant(), 'file-search-row-10');
    env.key('PageDown');
    assert.strictEqual(env.activeDescendant(), 'file-search-row-14');
  });

  test('Ctrl-N and Ctrl-P move like the arrows', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(5));
    env.key('n', {ctrlKey: true});
    assert.strictEqual(env.activeDescendant(), 'file-search-row-1');
    env.key('p', {ctrlKey: true});
    assert.strictEqual(env.activeDescendant(), 'file-search-row-0');
  });
});

describe('the worker', () => {
  test('being refused leaves a palette the reader can still dismiss', () => {
    // A policy that forbids workers rejects the constructor synchronously.
    // open() has revealed the overlay and not yet focused the input, so
    // throwing would leave a dialog that can be neither typed into nor closed.
    const env = load({
      workerFactory: () => {
        throw new Error('blocked by Content-Security-Policy');
      },
    });
    assert.doesNotThrow(() => env.globalKey('/'));
    assert.strictEqual(env.el.root.hidden, false);
    assert.strictEqual(env.doc.activeElement, env.el.input, 'the box must be typable');
    assert.strictEqual(env.status(), 'Search is unavailable on this page');
    env.key('Escape');
    assert.strictEqual(env.el.root.hidden, true, 'and Escape must still work');
  });

  test('is retried when the reader opens the palette again', () => {
    let calls = 0;
    const env = load({
      workerFactory: () => {
        if (++calls === 1) throw new Error('transient');
        return {
          sent: [],
          postMessage(m) {
            this.sent.push(m);
          },
          terminate() {},
          onmessage: null,
          onerror: null,
        };
      },
    });
    env.globalKey('/');
    assert.strictEqual(env.status(), 'Search is unavailable on this page');
    env.key('Escape');
    env.globalKey('/');
    assert.strictEqual(calls, 2, 'a one-off failure must not disable the finder for the page');
  });

  test('distinguishes failing to load from failing to search', () => {
    const env = load();
    env.globalKey('/');
    env.worker.reply({type: 'error', message: 'HTTP 503'});
    assert.strictEqual(env.status(), 'Could not load file index: HTTP 503');

    const env2 = opened();
    env2.type('x');
    env2.worker.reply({type: 'error', message: 'boom'});
    assert.strictEqual(env2.status(), 'Search failed: boom');
  });

  test('results overtaken by later keystrokes are dropped', () => {
    const env = opened();
    env.type('a');
    respond(env, ['dir/first.txt']);
    const stale = lastSent(env).seq;

    env.type('ab');
    respond(env, ['dir/overtaken.txt'], {seq: stale});
    const shown = () => env.el.list.children[0].children[0].textContent;
    assert.strictEqual(shown(), 'dir/first.txt', 'the stale answer must not paint');

    respond(env, ['dir/current.txt']);
    assert.strictEqual(shown(), 'dir/current.txt');
  });
});

describe('the status line', () => {
  test('reports an exact count plainly and a partial one with a plus', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(3), {total: 3, exact: true});
    assert.strictEqual(env.status(), '3 matches');

    respond(env, paths(LIMIT), {total: 40, exact: false});
    assert.strictEqual(env.status(), 'Showing 20 of 40+ matches');
  });

  test('says so when the exhaustive scan found nothing', () => {
    const env = opened();
    env.type('zzz');
    respond(env, []);
    assert.strictEqual(env.status(), 'No matching files');
  });
});

describe('what the reader keeps coming back to', () => {
  test('an opened file is remembered and offered on an empty box', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(3));
    env.key('Enter');

    const again = opened({storageMap: env.storage.map});
    assert.deepStrictEqual(lastSent(again), {
      type: 'recent',
      seq: 1,
      paths: ['dir/f000.txt'],
    });
  });

  test('a click is remembered the same as Enter', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(3));
    const {fire} = require('./fake_dom.js');
    fire(env.el.list, 'click', {target: env.el.list.children[1]});
    assert.match(env.storage.map.get([...env.storage.map.keys()][0]), /f001\.txt/);
  });

  test('scores halve every week, so frequency beats recency', () => {
    // s <- s * 2^(-dt / HALF_LIFE) + 1, so an entry untouched for a week is
    // worth half of one opened just now.
    const env = opened();
    env.type('f');
    respond(env, paths(3));
    env.key('Enter'); // f000, at t0

    const week = 7 * 24 * 60 * 60 * 1000;
    env.clock.advance(week);
    env.type('f');
    respond(env, paths(3));
    env.key('ArrowDown');
    env.key('Enter'); // f001, a week later

    const again = load({storageMap: env.storage.map});
    again.globalKey('/');
    again.worker.reply({type: 'ready', total: 3});
    assert.deepStrictEqual(
      lastSent(again).paths,
      ['dir/f001.txt', 'dir/f000.txt'],
      'f000 has decayed to 0.5, f001 is worth 1',
    );
  });

  test('the store is capped so a long-lived browser cannot grow without bound', () => {
    const env = load();
    const key = 'gitiles.file-finder.recent./repo';
    const seeded = [];
    for (let i = 0; i < 250; i++) {
      seeded.push({p: `dir/old${i}.txt`, s: 1, t: 1700000000000});
    }
    env.storage.map.set(key, JSON.stringify(seeded));
    env.globalKey('/');
    env.worker.reply({type: 'ready', total: 3});
    env.type('f');
    respond(env, paths(3));
    env.key('Enter');
    assert.strictEqual(JSON.parse(env.storage.map.get(key)).length, 200);
  });

  test('a storage that refuses to answer is tolerated', () => {
    const env = load({storage: {throwOnGet: true, throwOnSet: true}});
    env.globalKey('/');
    env.worker.reply({type: 'ready', total: 3});
    assert.strictEqual(lastSent(env).type, 'query', 'falls through to the ordinary listing');
    env.type('f');
    respond(env, paths(3));
    assert.doesNotThrow(() => env.key('Enter'));
    assert.deepStrictEqual(env.navigations, ['/repo/+/HEAD/dir/f000.txt']);
  });

  test('remembered entries are labelled as such, not as matches', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(3));
    env.key('Enter');

    const again = opened({storageMap: env.storage.map});
    respond(again, ['dir/f000.txt'], {recent: true});
    assert.strictEqual(again.status(), 'Recently opened');
  });
});

describe('warming', () => {
  test('the settled selection is warmed once', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(3));
    assert.strictEqual(env.fetches.length, 0, 'not before the selection settles');
    env.clock.advance(200);
    assert.deepStrictEqual(
      env.fetches.map((f) => f.url),
      ['/repo/+/HEAD/dir/f000.txt'],
    );
    assert.strictEqual(env.fetches[0].init.priority, 'low');
  });

  test('typing does not fire one request per keystroke', () => {
    const env = opened();
    for (const q of ['f', 'f0', 'f00', 'f000']) {
      env.type(q);
      respond(env, paths(3));
      env.clock.advance(50);
    }
    env.clock.advance(200);
    assert.strictEqual(env.fetches.length, 1);
  });

  test('closing leaves no standing speculation behind', () => {
    const env = opened();
    env.type('f');
    respond(env, paths(3));
    env.key('Escape');
    env.clock.advance(500);
    assert.strictEqual(env.fetches.length, 0);
  });
});

describe('dismissal', () => {
  test('the backdrop dismisses, the modal itself does not', () => {
    const {fire} = require('./fake_dom.js');
    const env = opened();
    fire(env.el.root, 'mousedown', {target: env.el.input});
    assert.strictEqual(env.el.root.hidden, false);
    fire(env.el.root, 'mousedown', {target: env.el.backdrop});
    assert.strictEqual(env.el.root.hidden, true);
  });

  test('leaving the page closes it, so the back/forward cache is clean', () => {
    const {fire} = require('./fake_dom.js');
    const env = opened();
    for (const fn of env.sandbox.window.listeners['pagehide'] || []) fn({});
    assert.strictEqual(env.el.root.hidden, true);
  });
});

describe('the host index', () => {
  const REPOS = [
    {name: 'platform/frameworks/base', href: '/platform/frameworks/base/', desc: 'The framework'},
    {name: 'platform/build', href: '/platform/build/'},
    // A repository may legitimately be called "constructor", so the name maps
    // must have no prototype.
    {name: 'constructor', href: '/constructor/', desc: 'Not a function'},
  ];

  function hostIndex(extra = {}) {
    const env = load(Object.assign({kind: 'repositories', repos: REPOS}, extra));
    env.globalKey('/');
    env.worker.reply({type: 'ready', total: REPOS.length});
    return env;
  }

  test('is read out of the page rather than fetched again', () => {
    // The page already lists every repository, and that response carries no
    // revision, so it is served no-store and would be re-fetched every load.
    const env = hostIndex();
    assert.deepStrictEqual(env.worker.sent[0], {
      type: 'load',
      names: REPOS.map((r) => r.name),
    });
    assert.strictEqual(env.fetches.length, 0);
  });

  test('links where the page links, and shows the description beside it', () => {
    const env = hostIndex();
    env.type('base');
    respond(env, ['platform/frameworks/base']);
    const a = env.el.list.children[0].children[0];
    assert.strictEqual(a.href, '/platform/frameworks/base/');
    const desc = a.children.find((c) => c.classList.contains('FileSearch-rowDesc'));
    assert.strictEqual(desc.textContent, 'The framework');
  });

  test('handles a repository named after an Object property', () => {
    const env = hostIndex();
    env.type('cons');
    respond(env, ['constructor']);
    const a = env.el.list.children[0].children[0];
    assert.strictEqual(a.href, '/constructor/');
  });

  test('never warms a repository page', () => {
    // A repository index names no revision, so it is served no-store: a
    // speculative fetch would be discarded and the request wasted.
    const env = hostIndex();
    env.type('base');
    respond(env, ['platform/frameworks/base']);
    env.clock.advance(1000);
    assert.strictEqual(env.fetches.length, 0);
  });

  test('offers no palette at all when the index is empty', () => {
    // An empty palette would be worse than none.
    const env = load({kind: 'repositories', repos: []});
    env.globalKey('/');
    assert.strictEqual(env.el.root.hidden, true);
    assert.strictEqual(env.workers.length, 0);
  });

  test('ignores a duplicate listing of the same repository', () => {
    const env = load({
      kind: 'repositories',
      repos: [REPOS[1], REPOS[1], REPOS[0]],
    });
    env.globalKey('/');
    assert.deepStrictEqual(lastSent(env).names, ['platform/build', 'platform/frameworks/base']);
  });
});

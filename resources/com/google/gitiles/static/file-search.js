// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * Finder UI. Press "/" to open, type to filter, Enter to go.
 *
 * One palette, two sources. A page that addresses a tree finds files, matching
 * against an index fetched on first open -- not on page load, so a reader who
 * never searches costs the server nothing. A host index finds repositories,
 * which that page already lists, so nothing is fetched at all.
 *
 * All matching happens in a Worker (file-search-worker.js); this file only
 * moves text in and paints rows out, so the input never stutters regardless of
 * how much there is to search.
 */
(function () {
  'use strict';

  /** Rows rendered. Must match LIMIT in the worker. */
  var LIMIT = 20;

  /** Rows PageUp/PageDown move by. Half a screen, so context is never lost. */
  var PAGE = 10;

  var root = document.getElementById('file-search');
  if (!root || typeof Worker === 'undefined') {
    return;
  }

  var workerUrl = root.getAttribute('data-worker-url');
  var treeUrl = root.getAttribute('data-tree-url');
  var repoMode = root.getAttribute('data-kind') === 'repositories';
  if (!workerUrl || (!repoMode && !treeUrl)) {
    return;
  }

  /** What is being searched, for the reader-facing copy. */
  var NOUN = repoMode ? 'repositories' : 'files';
  var NOUN_ONE = repoMode ? 'repository' : 'file';

  /**
   * The repositories this page lists: their names, links and descriptions.
   *
   * The host index renders every repository it knows about, so reading them
   * back out of the document is both cheaper than asking the server for the
   * same list again -- that response carries no revision and is therefore
   * served no-store, so it would be re-fetched on every page load -- and
   * exactly consistent with what the reader can see. Nothing can be missing
   * from the finder that is present on the page, because they are the same
   * list. The contract is the RepoList markup in HostIndex.soy.
   *
   * Names are used as keys, so the maps have no prototype: a repository may
   * legitimately be called "constructor".
   */
  function collectRepositories() {
    var names = [];
    var hrefs = Object.create(null);
    var descs = Object.create(null);
    var items = document.querySelectorAll('.RepoList .RepoList-item[href]');
    for (var i = 0; i < items.length; i++) {
      var nameEl = items[i].querySelector('.RepoList-itemName');
      var name = nameEl ? nameEl.textContent.trim() : '';
      if (!name || hrefs[name] !== undefined) {
        continue;
      }
      names.push(name);
      hrefs[name] = items[i].getAttribute('href');
      var descEl = items[i].querySelector('.RepoList-itemDescription');
      var desc = descEl ? descEl.textContent.trim() : '';
      if (desc) {
        descs[name] = desc;
      }
    }
    return {names: names, hrefs: hrefs, descs: descs};
  }

  var repos = repoMode ? collectRepositories() : null;
  if (repoMode && !repos.names.length) {
    // A host index with nothing on it. Offering an empty palette would be
    // worse than offering none.
    return;
  }

  // Base for file result links: the listing URL without its query string. It
  // ends in a slash, so appending an encoded path yields the file's page.
  // Repository links come from the page and need no base.
  var fileBase = repoMode ? null : treeUrl.split('?')[0];

  var input = root.querySelector('.FileSearch-input');
  var status = root.querySelector('.FileSearch-status');
  var list = root.querySelector('.FileSearch-results');

  var worker = null;
  var state = 'idle'; // idle | loading | ready | error
  var seq = 0;
  var lastRendered = -1;
  var selected = 0;
  var rows = [];
  var composing = false;
  var restoreFocus = null;
  var showingRecent = false;

  // -------------------------------------------------------------- frecency

  /**
   * What this reader has opened from the finder, most useful first.
   *
   * With an empty box there is nothing to match on, and listing alphabetically
   * is useless -- on chromium/src that offers ".gn", and on a host index the
   * first repository nobody has heard of. What a reader wants is the handful
   * of entries they keep coming back to.
   *
   * The score is an exponentially decayed visit count. On each visit
   *
   *     s <- s * 2^(-dt / HALF_LIFE) + 1
   *
   * which expands to the sum over past visits of 2^(-age / HALF_LIFE). So an
   * entry's weight halves for every week it goes untouched, frequent entries
   * outrank once-opened ones, and one abandoned months ago falls away without
   * ever needing a visit history: one number and one timestamp per entry
   * suffice, and updating is O(1).
   *
   * Storage is scoped to the listing it describes -- a repository for paths, a
   * host index for repository names, since a prefixed index lists them
   * relative to that prefix -- and is capped so a long-lived browser cannot
   * accumulate unbounded history. It records only what the reader opened
   * *through the finder*, never pages merely visited.
   */
  var RECENT_KEY =
    'gitiles.file-finder.recent.' +
    (repoMode ? 'repositories:' + location.pathname : fileBase.split('/+/')[0]);
  var RECENT_MAX = 200;
  var RECENT_HALF_LIFE_MS = 7 * 24 * 60 * 60 * 1000;

  /** Reads the store, tolerating absence, denial, and corruption alike. */
  function loadRecent() {
    var raw;
    try {
      raw = window.localStorage.getItem(RECENT_KEY);
    } catch (e) {
      // Storage can be disabled outright; the finder still works without it.
      return [];
    }
    if (!raw) {
      return [];
    }
    var parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (e) {
      return [];
    }
    if (!parsed || !parsed.length) {
      return [];
    }
    var out = [];
    for (var i = 0; i < parsed.length; i++) {
      var e = parsed[i];
      if (e && typeof e.p === 'string' && isFinite(e.s) && isFinite(e.t)) {
        out.push({p: e.p, s: e.s, t: e.t});
      }
    }
    return out;
  }

  function saveRecent(entries) {
    try {
      window.localStorage.setItem(RECENT_KEY, JSON.stringify(entries));
    } catch (e) {
      // A full or read-only store is not worth reporting to the reader.
    }
  }

  /** Score of `e` decayed to `now`. */
  function decayed(e, now) {
    return e.s * Math.pow(2, -(now - e.t) / RECENT_HALF_LIFE_MS);
  }

  /** Records that the reader opened `path` from the finder. */
  function recordVisit(path) {
    var now = Date.now();
    var entries = loadRecent();
    var found = null;
    for (var i = 0; i < entries.length; i++) {
      if (entries[i].p === path) {
        found = entries[i];
        break;
      }
    }
    if (found) {
      found.s = decayed(found, now) + 1;
      found.t = now;
    } else {
      entries.push({p: path, s: 1, t: now});
    }
    if (entries.length > RECENT_MAX) {
      entries.sort(function (a, b) {
        return decayed(b, now) - decayed(a, now);
      });
      entries.length = RECENT_MAX;
    }
    saveRecent(entries);
  }

  /** Remembered paths, best first. */
  function recentPaths() {
    var now = Date.now();
    var entries = loadRecent();
    entries.sort(function (a, b) {
      return decayed(b, now) - decayed(a, now);
    });
    var out = [];
    for (var i = 0; i < entries.length && i < RECENT_MAX; i++) {
      out.push(entries[i].p);
    }
    return out;
  }

  // ------------------------------------------------------------- rendering

  function makeRow() {
    var li = document.createElement('li');
    li.className = 'FileSearch-row';
    li.setAttribute('role', 'option');
    var a = document.createElement('a');
    a.className = 'FileSearch-link';
    li.appendChild(a);
    list.appendChild(li);
    return {li: li, a: a, path: null};
  }

  for (var i = 0; i < LIMIT; i++) {
    rows.push(makeRow());
  }

  /** Encodes a repository path for use in a URL, preserving separators. */
  function encodePath(path) {
    return path.split('/').map(encodeURIComponent).join('/');
  }

  /**
   * Paints one row, marking the matched characters.
   *
   * `ranges` are [start, length] pairs over the path, already merged and in
   * order by the worker.
   */
  function paint(row, item) {
    var a = row.a;
    // A repository's link is whatever the page linked to; a file's is composed
    // from the listing it was indexed from.
    a.href = repoMode ? repos.hrefs[item.path] : fileBase + encodePath(item.path);
    row.path = item.path;
    while (a.firstChild) {
      a.removeChild(a.firstChild);
    }
    var path = item.path;
    var ranges = item.ranges || [];
    var at = 0;
    for (var r = 0; r < ranges.length; r++) {
      var start = ranges[r][0];
      var len = ranges[r][1];
      if (start > at) {
        a.appendChild(document.createTextNode(path.substring(at, start)));
      }
      var mark = document.createElement('mark');
      mark.textContent = path.substring(start, start + len);
      a.appendChild(mark);
      at = start + len;
    }
    if (at < path.length) {
      a.appendChild(document.createTextNode(path.substring(at)));
    }
    if (repoMode && repos.descs[path]) {
      // Repository names alone are often not enough to choose between: the
      // page shows the description beside the name, so the finder does too.
      var desc = document.createElement('span');
      desc.className = 'FileSearch-rowDesc';
      desc.textContent = repos.descs[path];
      a.appendChild(desc);
    }
    row.li.hidden = false;
  }

  function render(items, total, exact) {
    for (var i = 0; i < LIMIT; i++) {
      if (i < items.length) {
        paint(rows[i], items[i]);
      } else {
        rows[i].li.hidden = true;
        rows[i].a.removeAttribute('href');
        rows[i].path = null;
      }
    }
    lastRendered = items.length;
    if (selected >= items.length) {
      selected = items.length ? items.length - 1 : 0;
    }
    highlight();

    if (!items.length) {
      // The scan is exhaustive, so this is trustworthy rather than a hedge.
      setStatus(input.value ? 'No matching ' + NOUN : '');
      return;
    }
    if (showingRecent) {
      // A count of remembered entries is not a match count, and calling it one
      // would be a lie the reader can check.
      setStatus(items.length === 1 ? 'Recently opened' : 'Recently opened ' + NOUN);
      return;
    }
    // A trailing "+" means the search stopped once the best tier filled the
    // display, so more paths match than were counted. See the worker protocol.
    var count = total.toLocaleString() + (exact ? '' : '+');
    if (total > items.length) {
      setStatus('Showing ' + items.length + ' of ' + count + ' matches');
    } else {
      setStatus(count + (total === 1 ? ' match' : ' matches'));
    }
  }

  function setStatus(text) {
    status.textContent = text;
  }

  function highlight() {
    for (var i = 0; i < LIMIT; i++) {
      var on = i === selected && !rows[i].li.hidden;
      rows[i].li.classList.toggle('FileSearch-row--selected', on);
      rows[i].li.setAttribute('aria-selected', on ? 'true' : 'false');
      if (on) {
        rows[i].li.scrollIntoView({block: 'nearest'});
      }
    }
    schedulePrefetch();
  }

  // ---------------------------------------------------------------- worker

  function ensureWorker() {
    if (worker) {
      return;
    }
    state = 'loading';
    setStatus('Loading ' + NOUN_ONE + ' index\u2026');
    worker = new Worker(workerUrl);
    worker.onmessage = function (ev) {
      var msg = ev.data;
      if (msg.type === 'ready') {
        state = 'ready';
        setStatus(msg.total.toLocaleString() + ' ' + NOUN);
        query();
      } else if (msg.type === 'results') {
        // Ignore responses overtaken by later keystrokes.
        if (msg.seq === seq) {
          render(msg.items, msg.total, msg.exact);
        }
      } else if (msg.type === 'error') {
        state = 'error';
        setStatus('Could not load ' + NOUN_ONE + ' index: ' + msg.message);
      }
    };
    worker.onerror = function () {
      state = 'error';
      setStatus('Could not load ' + NOUN_ONE + ' index');
    };
    worker.postMessage(
      repoMode ? {type: 'load', names: repos.names} : {type: 'load', url: treeUrl},
    );
  }

  function query() {
    if (state !== 'ready' || composing) {
      return;
    }
    seq++;
    // An empty box has nothing to match on, so offer what the reader keeps
    // coming back to. Falls through to the ordinary listing when there is
    // nothing remembered yet, which is every reader's first visit.
    if (!input.value) {
      var paths = recentPaths();
      if (paths.length) {
        showingRecent = true;
        worker.postMessage({type: 'recent', seq: seq, paths: paths});
        return;
      }
    }
    showingRecent = false;
    worker.postMessage({type: 'query', seq: seq, q: input.value});
  }

  // ------------------------------------------------------------------- open

  function open() {
    if (!root.hidden) {
      return;
    }
    restoreFocus = document.activeElement;
    root.hidden = false;
    document.body.classList.add('FileSearch-open');
    input.value = '';
    selected = 0;
    ensureWorker();
    input.focus();
    if (state === 'ready') {
      query();
    }
  }

  function close() {
    if (root.hidden) {
      return;
    }
    root.hidden = true;
    document.body.classList.remove('FileSearch-open');
    // A selection the user has abandoned is not worth a request, and a closed
    // palette should leave no standing speculation behind it.
    if (prefetchTimer !== null) {
      clearTimeout(prefetchTimer);
      prefetchTimer = null;
    }
    removeRule();
    if (restoreFocus && restoreFocus.focus) {
      restoreFocus.focus();
    }
    restoreFocus = null;
  }

  function navigate(ev) {
    var row = rows[selected];
    var a = row && row.a;
    if (!a || !a.href || row.li.hidden) {
      return;
    }
    if (row.path) {
      recordVisit(row.path);
    }
    if (ev && (ev.metaKey || ev.ctrlKey)) {
      window.open(a.href, '_blank');
    } else {
      window.location.href = a.href;
    }
  }

  // --------------------------------------------------------------- prefetch

  /**
   * Warms the browser cache with the highlighted result, so Enter paints from
   * cache instead of waiting on a round trip.
   *
   * This is only worth doing because result links are SHA-pinned and therefore
   * carry a real max-age; warming a no-store URL would be discarded and the
   * request wasted. That is exactly why repository results are never warmed: a
   * repository index page names no revision, so it is served no-store.
   *
   * Where the Speculation Rules API is available the warm is expressed as a
   * one-URL prefetch rule, which is the mechanism built for exactly this:
   * the request is labelled `Sec-Purpose: prefetch`, so an operator can see
   * speculative traffic in the logs and rate-limit or refuse it, and the
   * result lands in the prefetch cache the following navigation reads.
   * Elsewhere a low-priority same-origin `fetch` leaves an ordinary HTTP
   * cache entry instead, which the navigation also reads but which the server
   * cannot tell apart from a real request.
   *
   * `<link rel=prefetch>` is deliberately not used. Measured in Chrome
   * against a 506k-path repository, a link element warms some destinations
   * and cancels others with ERR_ABORTED under this access pattern -- many
   * warms on one page, no reload in between -- so it is unreliable precisely
   * where the finder needs it.
   *
   * Three limits keep it from being rude: a debounce, so typing a path does
   * not fire one request per keystroke and only a selection the user rests on
   * is warmed; a cap on distinct destinations, so a long session cannot fan
   * out without bound; and, on the fetch path, a dedupe set. Failures are
   * ignored by construction -- a warm that does not happen costs a cache
   * miss, nothing more.
   */
  var PREFETCH_DELAY_MS = 120;
  var PREFETCH_MAX = 32;
  var RULE_ID = 'file-search-speculation';
  var useRules = !!(
    window.HTMLScriptElement &&
    HTMLScriptElement.supports &&
    HTMLScriptElement.supports('speculationrules')
  );
  var prefetched = {};
  var prefetchCount = 0;
  var prefetchTimer = null;

  /**
   * Replaces the finder's speculation rule set with one naming `href`.
   *
   * The old script is removed rather than amended: a growing rule list would
   * ask the browser to hold every destination the selection has ever passed
   * over, and the cap below only bounds how many are named in total, not how
   * many are live at once.
   */
  function installRule(href) {
    removeRule();
    var script = document.createElement('script');
    script.id = RULE_ID;
    script.type = 'speculationrules';
    script.textContent = JSON.stringify({
      prefetch: [{source: 'list', urls: [href]}],
    });
    document.head.appendChild(script);
  }

  function removeRule() {
    var script = document.getElementById(RULE_ID);
    if (script) {
      script.remove();
    }
  }

  function fetchWarm(href) {
    // The body is read and dropped: the point is the cache entry it leaves
    // behind, not the text.
    fetch(href, {credentials: 'same-origin', priority: 'low'})
      .then(function (response) {
        return response.text();
      })
      .catch(function () {
        // A failed warm is not an error the reader needs to know about.
      });
  }

  function prefetch(href) {
    if (!prefetched[href]) {
      if (prefetchCount >= PREFETCH_MAX) {
        return;
      }
      prefetched[href] = true;
      prefetchCount++;
    } else if (!useRules) {
      // The HTTP cache still holds it, so arrowing back costs nothing.
      return;
    }
    // A rule set that is swapped out may take its prefetch with it, so a
    // destination revisited later has to be named again.
    if (useRules) {
      installRule(href);
    } else {
      fetchWarm(href);
    }
  }

  /** Queues a warm of whatever is selected once the selection settles. */
  function schedulePrefetch() {
    if (repoMode || (!useRules && typeof fetch !== 'function')) {
      return;
    }
    if (prefetchTimer !== null) {
      clearTimeout(prefetchTimer);
    }
    prefetchTimer = setTimeout(function () {
      prefetchTimer = null;
      var row = rows[selected];
      if (row && !row.li.hidden && row.a.href) {
        prefetch(row.a.href);
      }
    }, PREFETCH_DELAY_MS);
  }

  // Leaving the page must not put an open palette into the back/forward cache:
  // the browser restores the DOM verbatim, so Back would land the reader on a
  // page covered by a stale overlay. Closing here rather than repairing it on
  // `pageshow` means the snapshot is already clean, so there is no flash on
  // restore. Result links are SHA-pinned and therefore cacheable, which is
  // exactly what makes those pages bfcache-eligible.
  window.addEventListener('pagehide', function () {
    close();
  });

  // ------------------------------------------------------------------ input

  input.addEventListener('input', query);
  input.addEventListener('compositionstart', function () {
    composing = true;
  });
  input.addEventListener('compositionend', function () {
    composing = false;
    query();
  });

  /** Moves the selection by `delta` rows, clamped to what is displayed. */
  function move(delta) {
    if (lastRendered <= 0) {
      return;
    }
    var next = selected + delta;
    if (next < 0) {
      next = 0;
    } else if (next > lastRendered - 1) {
      next = lastRendered - 1;
    }
    if (next !== selected) {
      selected = next;
      highlight();
    }
  }

  input.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') {
      ev.preventDefault();
      close();
    } else if (ev.key === 'ArrowDown' || (ev.key === 'n' && ev.ctrlKey)) {
      ev.preventDefault();
      move(1);
    } else if (ev.key === 'ArrowUp' || (ev.key === 'p' && ev.ctrlKey)) {
      ev.preventDefault();
      move(-1);
    } else if (ev.key === 'PageDown') {
      // Without preventDefault the input scrolls the document behind the modal.
      ev.preventDefault();
      move(PAGE);
    } else if (ev.key === 'PageUp') {
      ev.preventDefault();
      move(-PAGE);
    } else if (ev.key === 'Enter') {
      ev.preventDefault();
      navigate(ev);
    }
  });

  // A row is an anchor, so a click navigates on its own without going through
  // navigate(). Record it here or clicking would be remembered differently
  // from pressing Enter on the same row.
  list.addEventListener('click', function (ev) {
    var li = ev.target.closest ? ev.target.closest('.FileSearch-row') : null;
    if (!li) {
      return;
    }
    for (var i = 0; i < LIMIT; i++) {
      if (rows[i].li === li && rows[i].path) {
        recordVisit(rows[i].path);
        return;
      }
    }
  });

  list.addEventListener('mousemove', function (ev) {
    var li = ev.target.closest ? ev.target.closest('.FileSearch-row') : null;
    if (!li) {
      return;
    }
    for (var i = 0; i < LIMIT; i++) {
      if (rows[i].li === li && selected !== i) {
        selected = i;
        highlight();
        return;
      }
    }
  });

  root.addEventListener('mousedown', function (ev) {
    // Clicking the backdrop dismisses; clicking inside the modal must not.
    if (ev.target === root || ev.target.classList.contains('FileSearch-backdrop')) {
      close();
    }
  });

  // ------------------------------------------------------------- global key

  function isEditable(el) {
    if (!el) {
      return false;
    }
    var tag = el.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
  }

  document.addEventListener('keydown', function (ev) {
    if (ev.key !== '/' || ev.metaKey || ev.ctrlKey || ev.altKey) {
      return;
    }
    if (isEditable(document.activeElement)) {
      return;
    }
    // Firefox binds "/" to Quick Find; take it over.
    ev.preventDefault();
    open();
  });

  var trigger = document.querySelector('.FileSearch-trigger');
  if (trigger) {
    trigger.addEventListener('click', function (ev) {
      ev.preventDefault();
      open();
    });
  }
})();

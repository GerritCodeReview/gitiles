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
 * File finder UI. Press "/" to open, type to filter, Enter to go.
 *
 * All matching happens in a Worker (file-search-worker.js); this file only
 * moves text in and paints rows out, so the input never stutters regardless of
 * repository size. The index is fetched on first open, not on page load, so a
 * user who never searches costs the server nothing.
 */
(function () {
  'use strict';

  /** Rows rendered. Must match LIMIT in the worker. */
  var LIMIT = 20;

  var root = document.getElementById('file-search');
  if (!root || typeof Worker === 'undefined') {
    return;
  }

  var treeUrl = root.getAttribute('data-tree-url');
  var workerUrl = root.getAttribute('data-worker-url');
  if (!treeUrl || !workerUrl) {
    return;
  }

  // Base for result links: the listing URL without its query string. It ends
  // in a slash, so appending an encoded path yields the file's page.
  var fileBase = treeUrl.split('?')[0];

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

  // ------------------------------------------------------------- rendering

  function makeRow() {
    var li = document.createElement('li');
    li.className = 'FileSearch-row';
    li.setAttribute('role', 'option');
    var a = document.createElement('a');
    a.className = 'FileSearch-link';
    li.appendChild(a);
    list.appendChild(li);
    return {li: li, a: a};
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
    a.href = fileBase + encodePath(item.path);
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
    row.li.hidden = false;
  }

  function render(items, total, exact) {
    for (var i = 0; i < LIMIT; i++) {
      if (i < items.length) {
        paint(rows[i], items[i]);
      } else {
        rows[i].li.hidden = true;
        rows[i].a.removeAttribute('href');
      }
    }
    lastRendered = items.length;
    if (selected >= items.length) {
      selected = items.length ? items.length - 1 : 0;
    }
    highlight();

    if (!items.length) {
      // The scan is exhaustive, so this is trustworthy rather than a hedge.
      setStatus(input.value ? 'No matching files' : '');
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
  }

  // ---------------------------------------------------------------- worker

  function ensureWorker() {
    if (worker) {
      return;
    }
    state = 'loading';
    setStatus('Loading file index\u2026');
    worker = new Worker(workerUrl);
    worker.onmessage = function (ev) {
      var msg = ev.data;
      if (msg.type === 'ready') {
        state = 'ready';
        setStatus(msg.total.toLocaleString() + ' files');
        query();
      } else if (msg.type === 'results') {
        // Ignore responses overtaken by later keystrokes.
        if (msg.seq === seq) {
          render(msg.items, msg.total, msg.exact);
        }
      } else if (msg.type === 'error') {
        state = 'error';
        setStatus('Could not load file index: ' + msg.message);
      }
    };
    worker.onerror = function () {
      state = 'error';
      setStatus('Could not load file index');
    };
    worker.postMessage({type: 'load', url: treeUrl});
  }

  function query() {
    if (state !== 'ready' || composing) {
      return;
    }
    seq++;
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
    if (restoreFocus && restoreFocus.focus) {
      restoreFocus.focus();
    }
    restoreFocus = null;
  }

  function navigate(ev) {
    var a = rows[selected] && rows[selected].a;
    if (!a || !a.href || rows[selected].li.hidden) {
      return;
    }
    if (ev && (ev.metaKey || ev.ctrlKey)) {
      window.open(a.href, '_blank');
    } else {
      window.location.href = a.href;
    }
  }

  // ------------------------------------------------------------------ input

  input.addEventListener('input', query);
  input.addEventListener('compositionstart', function () {
    composing = true;
  });
  input.addEventListener('compositionend', function () {
    composing = false;
    query();
  });

  input.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') {
      ev.preventDefault();
      close();
    } else if (ev.key === 'ArrowDown' || (ev.key === 'n' && ev.ctrlKey)) {
      ev.preventDefault();
      if (selected < lastRendered - 1) {
        selected++;
        highlight();
      }
    } else if (ev.key === 'ArrowUp' || (ev.key === 'p' && ev.ctrlKey)) {
      ev.preventDefault();
      if (selected > 0) {
        selected--;
        highlight();
      }
    } else if (ev.key === 'Enter') {
      ev.preventDefault();
      navigate(ev);
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

// Copyright 2026 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

(function() {
  // Filtering happens server-side, so each keystroke is a request. Wait for a
  // pause in typing before issuing one.
  const DEBOUNCE_MS = 200;
  const DEFAULT_MIN_LENGTH = 2;

  function initFileNavSearch() {
    const input = document.querySelector('[data-file-nav-search]');
    if (!input) {
      return;
    }
    // Row markup is shared with file-nav.js; without it, leave the static tree alone.
    const util = window.gitilesFileNav;
    if (!util) {
      return;
    }

    const nav = input.closest('.FileNav');
    const list = nav.querySelector('.FileNav-list');
    const results = nav.querySelector('.FileNav-results');
    const status = nav.querySelector('.FileNav-searchStatus');
    const searchUrl = input.dataset.fileNavSearch;
    const rootUrl = new URL(input.dataset.fileNavRoot, window.location.href);
    const minLength = parseInt(input.dataset.fileNavMinLength, 10) || DEFAULT_MIN_LENGTH;

    let pending = null;
    let debounceTimer = null;

    function setStatus(message) {
      status.textContent = message;
      status.hidden = !message;
    }

    function showTree() {
      results.textContent = '';
      results.hidden = true;
      list.hidden = false;
    }

    function resultUrl(path, isDirectory) {
      const encoded = path.split('/').map(encodeURIComponent).join('/');
      return new URL(encoded + (isDirectory ? '/' : ''), rootUrl).toString();
    }

    function appendHighlighted(target, text, query) {
      const lowerText = text.toLowerCase();
      let start = 0;
      let idx = query ? lowerText.indexOf(query) : -1;
      if (idx === -1) {
        target.appendChild(document.createTextNode(text));
        return;
      }
      while (idx !== -1) {
        if (idx > start) {
          target.appendChild(document.createTextNode(text.substring(start, idx)));
        }
        const mark = document.createElement('mark');
        mark.textContent = text.substring(idx, idx + query.length);
        target.appendChild(mark);
        start = idx + query.length;
        idx = lowerText.indexOf(query, start);
      }
      if (start < text.length) {
        target.appendChild(document.createTextNode(text.substring(start)));
      }
    }

    // Groups flat "a/b/c.txt" matches back into the directory structure, so results
    // read as a subset of the tree rather than a separate list of paths.
    function buildMatchTree(entries) {
      const root = {children: {}};
      entries.forEach(function(entry) {
        const parts = entry.name.split('/');
        let node = root;
        let pathSoFar = '';
        parts.forEach(function(part, index) {
          pathSoFar = pathSoFar ? pathSoFar + '/' + part : part;
          const isLeaf = index === parts.length - 1;
          if (!node.children[part]) {
            node.children[part] = {
              name: part,
              path: pathSoFar,
              isDirectory: !isLeaf,
              mode: null,
              children: {},
            };
          }
          node = node.children[part];
          if (isLeaf) {
            node.mode = entry.mode;
          }
        });
      });
      return root;
    }

    function renderMatchTree(node, container, query) {
      const names = Object.keys(node.children).sort(function(a, b) {
        const childA = node.children[a];
        const childB = node.children[b];
        if (childA.isDirectory !== childB.isDirectory) {
          return childA.isDirectory ? -1 : 1;
        }
        return a.toLowerCase().localeCompare(b.toLowerCase());
      });

      names.forEach(function(name) {
        const child = node.children[name];
        const isDirectory = child.isDirectory;
        const label = isDirectory ? child.name + '/' : child.name;

        const item = document.createElement('li');
        item.className =
            'FileNav-item ' + util.itemClass(isDirectory ? util.MODE_TREE : child.mode);

        const link = util.makeLink(label, resultUrl(child.path, isDirectory));
        const nameLabel = link.querySelector('.FileNav-name');
        nameLabel.textContent = '';
        appendHighlighted(nameLabel, label, query);
        item.appendChild(link);

        if (isDirectory) {
          const childList = document.createElement('ol');
          childList.className = 'FileNav-list FileNav-list--nested';
          renderMatchTree(child, childList, query);
          item.appendChild(childList);
        }
        container.appendChild(item);
      });
    }

    function render(data, query) {
      const entries = data.entries || [];
      results.textContent = '';
      renderMatchTree(buildMatchTree(entries), results, query);
      results.hidden = false;
      list.hidden = true;

      if (!entries.length) {
        setStatus('No files found');
      } else if (data.truncated) {
        // The server stopped early, so the real total is unknown -- do not invent one.
        setStatus('Showing the first ' + entries.length + ' matches');
      } else if (entries.length === 1) {
        setStatus('1 file found');
      } else {
        setStatus(entries.length + ' files found');
      }
    }

    function search(query) {
      const controller = new AbortController();
      pending = controller;
      const url = new URL(searchUrl, window.location.href);
      url.searchParams.set('filter', query);
      setStatus('Searching…');
      fetch(url, {headers: {Accept: 'application/json'}, signal: controller.signal})
        .then(function(response) {
          if (!response.ok) {
            throw new Error('File search failed');
          }
          return response.text();
        })
        .then(util.parseJson)
        .then(function(data) {
          // A newer query may have been typed while this one was in flight.
          if (controller.signal.aborted || input.value.trim().toLowerCase() !== query) {
            return;
          }
          render(data, query);
        })
        .catch(function() {
          if (controller.signal.aborted) {
            return;
          }
          // Never leave the sidebar with neither the tree nor any results.
          showTree();
          setStatus('File search is unavailable');
        });
    }

    input.addEventListener('input', function() {
      const query = input.value.trim().toLowerCase();
      if (debounceTimer !== null) {
        clearTimeout(debounceTimer);
        debounceTimer = null;
      }
      if (pending) {
        pending.abort();
        pending = null;
      }

      if (!query) {
        showTree();
        setStatus('');
        return;
      }
      if (query.length < minLength) {
        showTree();
        setStatus('Type at least ' + minLength + ' characters to search');
        return;
      }
      debounceTimer = setTimeout(function() {
        debounceTimer = null;
        search(query);
      }, DEBOUNCE_MS);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initFileNavSearch);
  } else {
    initFileNavSearch();
  }
})();

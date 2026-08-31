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
  // Git file modes, as reported by the `mode` field of the tree JSON API. Used to
  // pick the same icon classes the server renders for the root listing.
  const MODE_TREE = 0o040000;
  const MODE_SYMLINK = 0o120000;
  const MODE_EXECUTABLE_FILE = 0o100755;
  const MODE_GITLINK = 0o160000;

  // Mirrors TreeSoyData.TYPE_WEIGHT so lazily loaded levels sort like the root.
  function sortWeight(mode) {
    switch (mode) {
      case MODE_TREE:
        return 0;
      case MODE_GITLINK:
        return 1;
      case MODE_SYMLINK:
        return 2;
      default:
        return 3;
    }
  }

  function itemClass(mode) {
    switch (mode) {
      case MODE_TREE:
        return 'FileNav-item--gitTree';
      case MODE_SYMLINK:
        return 'FileNav-item--symlink';
      case MODE_EXECUTABLE_FILE:
        return 'FileNav-item--executableFile';
      case MODE_GITLINK:
        return 'FileNav-item--gitlink';
      default:
        return 'FileNav-item--regularFile';
    }
  }

  function parseJson(text) {
    return JSON.parse(text.replace(/^\)\]\}',?\n/, ''));
  }

  // Builds the same link markup the server renders, so enhanced rows keep the
  // .FileNav-name styling and stay real, navigable links.
  function makeLink(name, url) {
    const link = document.createElement('a');
    link.className = 'FileNav-link';
    link.href = url;
    const label = document.createElement('span');
    label.className = 'FileNav-name';
    label.textContent = name;
    link.appendChild(label);
    return link;
  }

  function initFileNav() {
    const nav = document.querySelector('.FileNav');
    if (!nav) {
      return;
    }
    const list = nav.querySelector('.FileNav-list');
    if (!list) {
      return;
    }

    function fetchTree(url) {
      const requestUrl = new URL(url, window.location.href);
      requestUrl.searchParams.set('format', 'JSON');
      requestUrl.searchParams.delete('recursive');
      return fetch(requestUrl, {headers: {Accept: 'application/json'}})
        .then(function(response) {
          if (!response.ok) {
            throw new Error('Tree request failed');
          }
          return response.text();
        })
        .then(parseJson);
    }

    function loadFolder(details, children) {
      if (details.dataset.loaded) {
        return Promise.resolve();
      }
      if (details.loadPromise) {
        return details.loadPromise;
      }
      details.loadPromise = fetchTree(details.folderUrl)
        .then(function(data) {
          appendEntries(
              children, data.entries || [], new URL(details.folderUrl, window.location.href));
          details.dataset.loaded = 'true';
        })
        .catch(function() {
          children.textContent = '';
          const error = document.createElement('li');
          error.className = 'FileNav-item FileNav-item--error';
          error.textContent = 'Folder contents are unavailable';
          children.appendChild(error);
        })
        .finally(function() {
          details.loadPromise = null;
        });
      return details.loadPromise;
    }

    // Wraps an existing folder link in a <details> disclosure. The link node is
    // moved rather than rebuilt, so any server-rendered state on it survives.
    function makeFolderDetails(link, url) {
      const details = document.createElement('details');
      details.folderUrl = url;
      const summary = document.createElement('summary');
      summary.className = 'FileNav-summary';
      summary.appendChild(link);
      details.appendChild(summary);

      const children = document.createElement('ol');
      children.className = 'FileNav-list FileNav-list--nested';
      details.appendChild(children);
      details.addEventListener('toggle', function() {
        if (details.open) {
          loadFolder(details, children);
        }
      });
      return details;
    }

    function makeEntryItem(entry, baseUrl) {
      const item = document.createElement('li');
      item.className = 'FileNav-item ' + itemClass(entry.mode);
      const isFolder = entry.mode === MODE_TREE;
      const name = isFolder ? entry.name + '/' : entry.name;
      const href = new URL(
          encodeURIComponent(entry.name) + (isFolder ? '/' : ''), baseUrl);
      const link = makeLink(name, href.toString());
      if (isFolder) {
        item.appendChild(makeFolderDetails(link, href));
      } else {
        item.appendChild(link);
      }
      return item;
    }

    function appendEntries(target, entries, baseUrl) {
      target.textContent = '';
      entries
        .slice()
        .sort(function(a, b) {
          const weightDiff = sortWeight(a.mode) - sortWeight(b.mode);
          if (weightDiff !== 0) {
            return weightDiff;
          }
          return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
        })
        .forEach(function(entry) {
          target.appendChild(makeEntryItem(entry, baseUrl));
        });
    }

    list.querySelectorAll('[data-file-nav-folder]').forEach(function(link) {
      const item = link.closest('.FileNav-item');
      const folderUrl = new URL(link.getAttribute('href'), window.location.href);
      item.appendChild(makeFolderDetails(link, folderUrl));
    });

    revealActivePath(nav, loadFolder);
    initResizer(nav);
    initStickyHeader(nav);
  }

  function revealActivePath(nav, loadFolder) {
    const activePath = nav.dataset.fileNavActivePath;
    if (!activePath) {
      return;
    }
    const segments = activePath.split('/').filter(Boolean);
    if (!segments.length) {
      return;
    }

    function linkOf(item) {
      return item.querySelector(
          ':scope > .FileNav-link, :scope > details > summary > .FileNav-link');
    }

    function findItem(container, name) {
      const items = container.querySelectorAll(':scope > .FileNav-item');
      for (let i = 0; i < items.length; i++) {
        const link = linkOf(items[i]);
        if (link && link.textContent === name) {
          return items[i];
        }
      }
      return null;
    }

    function markActive(item) {
      item.classList.add('FileNav-item--active');
      const link = linkOf(item);
      if (link) {
        link.setAttribute('aria-current', 'page');
      }
    }

    function next(container, remaining) {
      if (!remaining.length || !container) {
        return;
      }
      const isLast = remaining.length === 1;
      const target = remaining[0];
      const rest = remaining.slice(1);

      const dirItem = findItem(container, target + '/');
      if (dirItem) {
        const details = dirItem.querySelector(':scope > details');
        if (details) {
          details.open = true;
          const children = details.querySelector(':scope > .FileNav-list--nested');
          loadFolder(details, children).then(function() {
            if (rest.length) {
              next(children, rest);
            } else {
              markActive(dirItem);
              dirItem.scrollIntoView({block: 'center'});
            }
          });
          return;
        }
      }

      if (isLast) {
        const fileItem = findItem(container, target);
        if (fileItem) {
          markActive(fileItem);
          fileItem.scrollIntoView({block: 'center'});
        }
      }
    }

    next(nav.querySelector('.FileNav-list'), segments);
  }

  function initStickyHeader(nav) {
    const summary = nav.querySelector('.FileNav-disclosure > .FileNav-summary');
    if (!summary) {
      return;
    }

    function updateOffset() {
      nav.style.setProperty(
          '--file-nav-summary-height', summary.getBoundingClientRect().height + 'px');
    }

    updateOffset();
    if (window.ResizeObserver) {
      new ResizeObserver(updateOffset).observe(summary);
    } else {
      window.addEventListener('resize', updateOffset);
    }
  }

  const MIN_WIDTH = 160;
  const MAX_WIDTH = 640;
  // Arrow keys nudge the splitter; holding shift moves it in coarser steps.
  const KEY_STEP = 10;
  const KEY_STEP_COARSE = 40;
  const STORAGE_KEY = 'gitiles-file-nav-width';

  function initResizer(nav) {
    const resizer = document.querySelector('.PathDetail-resizer');
    if (!resizer) {
      return;
    }

    function clampWidth(width) {
      return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, width));
    }

    function setWidth(width) {
      if (!isFinite(width)) {
        return;
      }
      width = clampWidth(width);
      nav.style.flexBasis = width + 'px';
      resizer.setAttribute('aria-valuenow', String(Math.round(width)));
      try {
        sessionStorage.setItem(STORAGE_KEY, String(Math.round(width)));
      } catch (e) {}
    }

    // Only advertise the splitter once it can actually be operated.
    resizer.setAttribute('role', 'separator');
    resizer.setAttribute('aria-orientation', 'vertical');
    resizer.setAttribute('aria-controls', 'gitiles-file-nav');
    resizer.setAttribute('aria-label', 'Resize file navigation');
    resizer.setAttribute('tabindex', '0');
    resizer.setAttribute('aria-valuemin', String(MIN_WIDTH));
    resizer.setAttribute('aria-valuemax', String(MAX_WIDTH));

    let stored = null;
    try {
      stored = sessionStorage.getItem(STORAGE_KEY);
    } catch (e) {}
    const storedWidth = stored !== null ? parseInt(stored, 10) : NaN;
    if (isFinite(storedWidth)) {
      setWidth(storedWidth);
    } else {
      resizer.setAttribute('aria-valuenow', String(Math.round(nav.getBoundingClientRect().width)));
    }

    let dragStartX = null;
    let dragStartWidth = null;

    function onPointerMove(e) {
      if (dragStartX === null) {
        return;
      }
      setWidth(dragStartWidth + (e.clientX - dragStartX));
    }

    function onPointerUp() {
      dragStartX = null;
      dragStartWidth = null;
      document.body.classList.remove('is-resizingFileNav');
      document.removeEventListener('pointermove', onPointerMove);
      document.removeEventListener('pointerup', onPointerUp);
    }

    resizer.addEventListener('pointerdown', function(e) {
      dragStartX = e.clientX;
      dragStartWidth = nav.getBoundingClientRect().width;
      document.body.classList.add('is-resizingFileNav');
      document.addEventListener('pointermove', onPointerMove);
      document.addEventListener('pointerup', onPointerUp);
      e.preventDefault();
    });

    resizer.addEventListener('keydown', function(e) {
      const step = e.shiftKey ? KEY_STEP_COARSE : KEY_STEP;
      const current = nav.getBoundingClientRect().width;
      if (e.key === 'ArrowLeft') {
        setWidth(current - step);
        e.preventDefault();
      } else if (e.key === 'ArrowRight') {
        setWidth(current + step);
        e.preventDefault();
      } else if (e.key === 'Home') {
        setWidth(MIN_WIDTH);
        e.preventDefault();
      } else if (e.key === 'End') {
        setWidth(MAX_WIDTH);
        e.preventDefault();
      }
    });

    resizer.addEventListener('dblclick', function() {
      nav.style.flexBasis = '';
      try {
        sessionStorage.removeItem(STORAGE_KEY);
      } catch (e) {}
      resizer.setAttribute(
          'aria-valuenow', String(Math.round(nav.getBoundingClientRect().width)));
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initFileNav);
  } else {
    initFileNav();
  }

  // Shared with file-nav-search.js so search results and the tree build rows
  // from one set of rules and cannot drift apart.
  window.gitilesFileNav = {
    MODE_TREE: MODE_TREE,
    itemClass: itemClass,
    makeLink: makeLink,
    parseJson: parseJson,
  };
})();

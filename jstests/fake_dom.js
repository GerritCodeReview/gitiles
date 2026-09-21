// A recording stand-in for the browser APIs file-search.js uses.
//
// What this is
// ------------
// file-search.js is an IIFE that exports nothing and starts by looking for
// #file-search in the document. The only way to reach it is to give it a
// document. This builds the smallest one that lets it run, and records what it
// does to it.
//
// What it models faithfully
// -------------------------
// Bookkeeping the finder performs itself: attributes set and removed, classes
// toggled, children appended, listeners registered and called, timers queued,
// storage read and written. Assertions about those are assertions about our
// code, and a recording cannot drift from what was recorded.
//
// What it does NOT model, and must not be asserted on
// ---------------------------------------------------
//   * Event bubbling. `fire()` invokes listeners on the element they were
//     registered on; a test names the target explicitly. The finder's own
//     handlers use `ev.target.closest(...)`, which is honoured, but a test
//     must not expect a click on a child to reach a parent's listener.
//   * Focus semantics beyond "focus() was called, and activeElement moved".
//   * Layout. scrollIntoView is counted, nothing more.
//   * Content-Security-Policy, speculation rules, prefetch caching and
//     navigation. Those are observed as "the finder asked", never as "the
//     browser complied". The headless-browser checks cover the rest.
//
// Selector support is deliberately partial: descendant chains of class
// selectors with an optional attribute-presence test, which is everything
// file-search.js asks for and nothing more. An unsupported selector throws
// rather than quietly matching nothing.

'use strict';

const fs = require('node:fs');
const vm = require('node:vm');

const SRC = 'resources/com/google/gitiles/static/file-search.js';

// ------------------------------------------------------------------ nodes

class TextNode {
  constructor(text) {
    this.nodeType = 3;
    this.textContent = text;
    this.parentNode = null;
  }
  get text() {
    return this.textContent;
  }
}

class ClassList {
  constructor(el) {
    this.el = el;
  }
  _set() {
    return new Set(this.el.className ? this.el.className.split(/\s+/).filter(Boolean) : []);
  }
  _write(s) {
    this.el.className = Array.from(s).join(' ');
  }
  add(n) {
    const s = this._set();
    s.add(n);
    this._write(s);
  }
  remove(n) {
    const s = this._set();
    s.delete(n);
    this._write(s);
  }
  contains(n) {
    return this._set().has(n);
  }
  toggle(n, force) {
    const on = force === undefined ? !this.contains(n) : !!force;
    if (on) this.add(n);
    else this.remove(n);
    return on;
  }
}

class Element {
  constructor(tagName, doc) {
    this.tagName = String(tagName).toUpperCase();
    this.ownerDocument = doc;
    this.nodeType = 1;
    this.childNodes = [];
    this.parentNode = null;
    this.attributes = Object.create(null);
    this.className = '';
    this.classList = new ClassList(this);
    this.hidden = false;
    this.listeners = Object.create(null);
    this.scrollCount = 0;
    this.focusCount = 0;
  }

  // --- attributes. id/href/tabIndex/type are properties the finder sets
  // directly as well as through setAttribute, so keep the two in step.
  setAttribute(n, v) {
    this.attributes[n] = String(v);
  }
  getAttribute(n) {
    return n in this.attributes ? this.attributes[n] : null;
  }
  removeAttribute(n) {
    delete this.attributes[n];
  }
  hasAttribute(n) {
    return n in this.attributes;
  }

  get id() {
    return this.getAttribute('id') || '';
  }
  set id(v) {
    this.setAttribute('id', v);
  }
  get href() {
    return this.getAttribute('href');
  }
  set href(v) {
    this.setAttribute('href', v);
  }
  get tabIndex() {
    return this.hasAttribute('tabindex') ? Number(this.getAttribute('tabindex')) : 0;
  }
  set tabIndex(v) {
    this.setAttribute('tabindex', v);
  }

  // --- children
  appendChild(child) {
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    this.childNodes.push(child);
    return child;
  }
  removeChild(child) {
    const i = this.childNodes.indexOf(child);
    if (i >= 0) this.childNodes.splice(i, 1);
    child.parentNode = null;
    return child;
  }
  remove() {
    if (this.parentNode) this.parentNode.removeChild(this);
  }
  get firstChild() {
    return this.childNodes.length ? this.childNodes[0] : null;
  }
  get children() {
    return this.childNodes.filter((n) => n.nodeType === 1);
  }

  get textContent() {
    return this.childNodes.map((n) => n.textContent).join('');
  }
  set textContent(v) {
    this.childNodes = [];
    if (v !== '') this.appendChild(new TextNode(String(v)));
  }

  // --- queries
  querySelector(sel) {
    return descendants(this).find((e) => matches(e, sel)) || null;
  }
  querySelectorAll(sel) {
    return descendants(this).filter((e) => matches(e, sel));
  }
  closest(sel) {
    for (let e = this; e; e = e.parentNode) {
      if (e.nodeType === 1 && matches(e, sel)) return e;
    }
    return null;
  }

  // --- events and the two side effects worth counting
  addEventListener(type, fn) {
    (this.listeners[type] || (this.listeners[type] = [])).push(fn);
  }
  scrollIntoView() {
    this.scrollCount++;
  }
  focus() {
    this.focusCount++;
    if (this.ownerDocument) this.ownerDocument.activeElement = this;
  }
}

function descendants(el) {
  const out = [];
  const walk = (n) => {
    for (const c of n.childNodes) {
      if (c.nodeType !== 1) continue;
      out.push(c);
      walk(c);
    }
  };
  walk(el);
  return out;
}

// --------------------------------------------------------------- selectors

/** Parses ".A .B[attr]" into a list of simple selectors, innermost last. */
function parse(sel) {
  return sel
    .trim()
    .split(/\s+/)
    .map((part) => {
      const m = /^(\.[A-Za-z0-9_-]+|[A-Za-z]+)(?:\[([A-Za-z-]+)\])?$/.exec(part);
      if (!m) throw new Error(`fake_dom: unsupported selector ${JSON.stringify(sel)}`);
      return {
        cls: m[1][0] === '.' ? m[1].slice(1) : null,
        tag: m[1][0] === '.' ? null : m[1].toUpperCase(),
        attr: m[2] || null,
      };
    });
}

function simpleMatches(el, s) {
  if (s.cls && !el.classList.contains(s.cls)) return false;
  if (s.tag && el.tagName !== s.tag) return false;
  if (s.attr && !el.hasAttribute(s.attr)) return false;
  return true;
}

function matches(el, sel) {
  const parts = parse(sel);
  if (!simpleMatches(el, parts[parts.length - 1])) return false;
  let i = parts.length - 2;
  for (let e = el.parentNode; e && i >= 0; e = e.parentNode) {
    if (e.nodeType === 1 && simpleMatches(e, parts[i])) i--;
  }
  return i < 0;
}

// --------------------------------------------------------------- the world

class Document {
  constructor() {
    this.nodeType = 9;
    this.childNodes = [];
    this.listeners = Object.create(null);
    this.activeElement = null;
    this.head = new Element('head', this);
    this.body = new Element('body', this);
    this.appendChild(this.head);
    this.appendChild(this.body);
    // A real document always has an active element; it is the body when
    // nothing else has focus. Starting at null made "/" look like it was
    // being typed into whatever was focused last.
    this.activeElement = this.body;
  }
  createElement(tag) {
    return new Element(tag, this);
  }
  createTextNode(t) {
    return new TextNode(t);
  }
  appendChild(c) {
    c.parentNode = this;
    this.childNodes.push(c);
    return c;
  }
  getElementById(id) {
    return descendants(this).find((e) => e.id === id) || null;
  }
  querySelector(sel) {
    return descendants(this).find((e) => matches(e, sel)) || null;
  }
  querySelectorAll(sel) {
    return descendants(this).filter((e) => matches(e, sel));
  }
  addEventListener(type, fn) {
    (this.listeners[type] || (this.listeners[type] = [])).push(fn);
  }
}

/** Invokes the listeners registered on `el` for `type`. No bubbling. */
function fire(el, type, event) {
  const ev = Object.assign({target: el, preventDefaultCount: 0}, event);
  ev.preventDefault = () => {
    ev.preventDefaultCount++;
  };
  for (const fn of el.listeners[type] || []) fn(ev);
  return ev;
}

/** A clock the test drives, standing in for both timers and Date.now. */
class Clock {
  constructor() {
    this.now = 1700000000000;
    this.timers = new Map();
    this.next = 1;
  }
  setTimeout(fn, ms) {
    const id = this.next++;
    this.timers.set(id, {at: this.now + (ms || 0), fn});
    return id;
  }
  clearTimeout(id) {
    this.timers.delete(id);
  }
  /** Runs everything due within `ms`, in time order. */
  advance(ms) {
    const until = this.now + ms;
    for (;;) {
      let due = null;
      for (const [id, t] of this.timers) {
        if (t.at <= until && (due === null || t.at < due[1].at)) due = [id, t];
      }
      if (!due) break;
      this.timers.delete(due[0]);
      this.now = due[1].at;
      due[1].fn();
    }
    this.now = until;
  }
}

class FakeStorage {
  constructor(opts) {
    this.map = new Map();
    this.throwOnGet = (opts && opts.throwOnGet) || false;
    this.throwOnSet = (opts && opts.throwOnSet) || false;
  }
  getItem(k) {
    if (this.throwOnGet) throw new Error('storage disabled');
    return this.map.has(k) ? this.map.get(k) : null;
  }
  setItem(k, v) {
    if (this.throwOnSet) throw new Error('storage full');
    this.map.set(k, String(v));
  }
}

// ------------------------------------------------------------------ set-up

/**
 * Builds the markup Common.soy renders for the palette.
 *
 * Kept to what file-search.js actually reaches for: the root and its three
 * children, plus the trigger. The real template carries a backdrop and
 * labelling this does not need.
 */
function buildPalette(doc, {workerUrl, treeUrl}) {
  const root = doc.createElement('div');
  root.id = 'file-search';
  root.hidden = true;
  root.setAttribute('data-worker-url', workerUrl);
  root.setAttribute('data-tree-url', treeUrl);

  const backdrop = doc.createElement('div');
  backdrop.className = 'FileSearch-backdrop';
  root.appendChild(backdrop);

  const input = doc.createElement('input');
  input.className = 'FileSearch-input';
  input.value = '';
  root.appendChild(input);

  const status = doc.createElement('div');
  status.className = 'FileSearch-status';
  root.appendChild(status);

  const list = doc.createElement('ul');
  list.className = 'FileSearch-results';
  root.appendChild(list);

  doc.body.appendChild(root);

  const trigger = doc.createElement('button');
  trigger.className = 'FileSearch-trigger';
  doc.body.appendChild(trigger);

  return {root, backdrop, input, status, list, trigger};
}

/**
 * Loads file-search.js against a fresh fake document.
 *
 * Options:
 *   workerFactory (url) => fake worker, or a function that throws to model a
 *                 constructor refused by policy
 *   noWorker      omit the Worker global entirely
 *   storage       {throwOnGet, throwOnSet}
 *   speculation   whether HTMLScriptElement.supports('speculationrules') is true
 */
function load(options = {}) {
  const doc = new Document();
  const clock = new Clock();
  const el = buildPalette(doc, {
    workerUrl: '/+static/file-search-worker.js',
    treeUrl: '/repo/+/HEAD/?format=JSON&paths_only=1',
  });

  const workers = [];
  const navigations = [];
  const opened = [];
  const fetches = [];
  const storage = new FakeStorage(options.storage);
  // A second page load sees whatever the first one left behind.
  if (options.storageMap) storage.map = options.storageMap;

  function defaultWorkerFactory() {
    const w = {
      sent: [],
      terminated: 0,
      onmessage: null,
      onerror: null,
      postMessage(m) {
        // A real postMessage structured-clones across the thread boundary, so
        // the worker never receives the page's own objects. Modelling that
        // also keeps recorded messages in this realm, where deepStrictEqual's
        // prototype check can pass.
        this.sent.push(JSON.parse(JSON.stringify(m)));
      },
      terminate() {
        this.terminated++;
      },
      /** Delivers a message as the real worker would. */
      reply(msg) {
        if (this.onmessage) this.onmessage({data: msg});
      },
      fail() {
        if (this.onerror) this.onerror({});
      },
    };
    return w;
  }

  const factory = options.workerFactory || defaultWorkerFactory;

  const location = {pathname: '/', href: '/'};
  const windowObj = {
    localStorage: storage,
    listeners: Object.create(null),
    addEventListener(type, fn) {
      (this.listeners[type] || (this.listeners[type] = [])).push(fn);
    },
    open(href, target) {
      opened.push({href, target});
    },
    get location() {
      return location;
    },
  };
  if (options.speculation) {
    windowObj.HTMLScriptElement = {supports: (s) => s === 'speculationrules'};
  }
  // window.location.href = ... must be observable as a navigation.
  Object.defineProperty(location, 'href', {
    get: () => location._href || '/',
    set: (v) => {
      location._href = v;
      navigations.push(v);
    },
  });

  const sandbox = {
    document: doc,
    window: windowObj,
    location,
    console,
    JSON,
    Math,
    Object,
    Array,
    String,
    Number,
    isFinite,
    encodeURIComponent,
    Date: {now: () => clock.now},
    setTimeout: (fn, ms) => clock.setTimeout(fn, ms),
    clearTimeout: (id) => clock.clearTimeout(id),
    fetch: (url, init) => {
      fetches.push({url, init});
      return Promise.resolve({text: () => Promise.resolve('')});
    },
  };
  if (!options.noWorker) {
    sandbox.Worker = function (url) {
      const w = factory(url);
      workers.push(w);
      return w;
    };
  }
  sandbox.HTMLScriptElement = windowObj.HTMLScriptElement;

  vm.runInNewContext(fs.readFileSync(SRC, 'utf8'), sandbox, {filename: SRC});

  return {
    doc,
    clock,
    el,
    storage,
    workers,
    navigations,
    opened,
    fetches,
    sandbox,
    /** The worker most recently constructed. */
    get worker() {
      return workers[workers.length - 1];
    },
    /** Presses a key in the input. */
    key(k, extra) {
      return fire(el.input, 'keydown', Object.assign({key: k}, extra));
    },
    /** Presses a key on the document, as a reader not in the palette would. */
    globalKey(k, extra) {
      return fire(doc, 'keydown', Object.assign({key: k, target: doc.body}, extra));
    },
    /** Types into the input and lets the finder react. */
    type(text) {
      el.input.value = text;
      fire(el.input, 'input', {});
    },
    /** The rows currently painted, in display order. */
    visibleRows() {
      return el.list.children.filter((li) => !li.hidden);
    },
    /** The id named by aria-activedescendant, or null. */
    activeDescendant() {
      return el.input.getAttribute('aria-activedescendant');
    },
    status() {
      return el.status.textContent;
    },
  };
}

module.exports = {load, fire, Element, Document, Clock};

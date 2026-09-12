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
 * File finder search engine. Runs in a Worker so that no amount of index work
 * can drop a frame on the main thread.
 *
 * Protocol:
 *   in  {type: 'load', url}     -> out {type: 'ready', total}
 *                                | out {type: 'error', message}
 *   in  {type: 'query', seq, q} -> out {type: 'results', seq, q, total, exact,
 *                                        items}
 *
 * `items` is ranked and cut to the display limit. The display is limited; the
 * search never is. If a path exists and matches, either it is shown or a
 * strictly better-ranked path took its place.
 *
 * `total` counts the matches in the tier that produced `items` (see `search`).
 * When a cheap tier already fills the display, the expensive tier is never run,
 * so `total` is then a lower bound on all matching paths and `exact` is false.
 * Making it exact would cost a full-corpus scan on every keystroke to refine a
 * number nobody navigates by.
 *
 * The index is deliberately string-free. Paths live as bytes in two flat
 * buffers (directories, basenames) with Int32Array offsets, so the hot loops
 * walk contiguous memory and the collector never sees half a million objects.
 * JS strings are materialised only for the rows actually displayed.
 */
'use strict';

/** Rows the UI shows. Ranking decides which; the scan still covers everything. */
const LIMIT = 20;

// Character classes for the 32-bit membership bitset, used as a reject filter.
// Case folded, with the separators that matter for path matching given their
// own bits. Bytes >= 0x80 all share bucket 31.
const CLS = new Uint8Array(256).fill(31);
for (let c = 0x61; c <= 0x7a; c++) CLS[c] = c - 0x61; // a-z
for (let c = 0x41; c <= 0x5a; c++) CLS[c] = c - 0x41; // A-Z, folded
for (let c = 0x30; c <= 0x39; c++) CLS[c] = 26; // 0-9 share one bit
CLS[0x2e] = 27; // .
CLS[0x5f] = 28; // _
CLS[0x2d] = 29; // -
CLS[0x2f] = 30; // /

/** ASCII lowercase fold, applied per byte during comparison. */
const LOW = new Uint8Array(256);
for (let c = 0; c < 256; c++) LOW[c] = c >= 0x41 && c <= 0x5a ? c + 0x20 : c;

/** Bytes that begin a new word for scoring purposes. */
const SEP = new Uint8Array(256);
SEP[0x2e] = 1;
SEP[0x5f] = 1;
SEP[0x2d] = 1;
SEP[0x2f] = 1;
SEP[0x20] = 1;

const SLASH = 0x2f;

/** Stand-in for "no query", so an empty box allocates nothing. */
const EMPTY = new Uint8Array(0);

/**
 * Shared UTF-8 encoder. It holds no state between calls -- `encode` returns a
 * fresh array, `encodeInto` writes into a caller-supplied one -- and a worker
 * is single-threaded, so one instance serves every caller. `setQuery` runs on
 * every keystroke, which is where building one per call would otherwise show
 * up.
 */
const ENC = new TextEncoder();

function isUpper(b) {
  return b >= 0x41 && b <= 0x5a;
}

function isLower(b) {
  return b >= 0x61 && b <= 0x7a;
}

function maskOf(buf, s, e) {
  let m = 0;
  for (let i = s; i < e; i++) m |= 1 << CLS[buf[i]];
  return m;
}

function grow(buf, need) {
  const next = new Uint8Array(Math.max(need, buf.length * 2));
  next.set(buf);
  return next;
}

/** @type {?Index} */
let index = null;

/**
 * Flat, string-free index over every path in a tree.
 *
 * Directories are stored once per run rather than once per file. The server
 * emits paths in tree order, which is byte-lexicographic, so files sharing a
 * directory are adjacent and the runs fall out of one pass. On chromium/src
 * this collapses 506,237 paths into 46,286 directories, which is the reduction
 * that makes the full-path channel affordable.
 */
class Index {
  constructor(paths) {
    const n = paths.length;

    let totalLen = 0;
    for (let i = 0; i < n; i++) totalLen += paths[i].length;
    // UTF-8 can expand relative to UTF-16 length; grow on demand rather than
    // reserving three bytes per character for a corpus that is nearly all
    // ASCII.
    let baseBuf = new Uint8Array(totalLen + 64);
    let dirBuf = new Uint8Array(totalLen + 64);

    const baseOff = new Int32Array(n + 1);
    const dirId = new Int32Array(n);
    const dirOffTmp = new Int32Array(n + 1);

    let bp = 0;
    let dp = 0;
    let nd = 0;
    let prevDir = null;

    for (let i = 0; i < n; i++) {
      const p = paths[i];
      const slash = p.lastIndexOf('/');
      const dir = slash < 0 ? '' : p.substring(0, slash);
      const base = slash < 0 ? p : p.substring(slash + 1);

      if (prevDir === null || dir !== prevDir) {
        if (dp + dir.length * 3 > dirBuf.length) {
          dirBuf = grow(dirBuf, dp + dir.length * 3);
        }
        dirOffTmp[nd] = dp;
        dp += ENC.encodeInto(dir, dirBuf.subarray(dp)).written;
        nd++;
        prevDir = dir;
      }
      dirId[i] = nd - 1;

      if (bp + base.length * 3 > baseBuf.length) {
        baseBuf = grow(baseBuf, bp + base.length * 3);
      }
      baseOff[i] = bp;
      bp += ENC.encodeInto(base, baseBuf.subarray(bp)).written;
    }
    baseOff[n] = bp;
    dirOffTmp[nd] = dp;

    this.n = n;
    this.d = nd;
    this.baseBuf = baseBuf;
    this.baseOff = baseOff;
    this.dirBuf = dirBuf;
    this.dirOff = dirOffTmp.slice(0, nd + 1);
    this.dirId = dirId;

    this.baseMask = new Int32Array(n);
    for (let i = 0; i < n; i++) {
      this.baseMask[i] = maskOf(baseBuf, baseOff[i], baseOff[i + 1]);
    }

    // Files are contiguous within a directory run, so a run is an index range.
    // Knowing it lets the full-path channel accept a whole directory at once
    // when the directory alone already satisfies the query.
    this.dirEnd = new Int32Array(nd);
    for (let i = 0; i < n; i++) {
      this.dirEnd[dirId[i]] = i + 1;
    }

    // Byte-length of the prefix each directory shares with its predecessor.
    // Paths arrive sorted, so adjacent directory runs such as .../core/html
    // and .../core/svg usually differ only in their tails; the greedy query
    // match over the shared head is identical and can be resumed rather than
    // recomputed. This is what makes the per-directory pass cheap.
    //
    // Note the run list itself is not sorted and may repeat: a/x, a/b/y, a/z
    // yields runs a, a/b, a. Nothing here relies on ordering, only on this
    // being the literal shared prefix of two adjacent entries, so repeats are
    // harmless (they simply produce a large lcp and no work at all).
    this.dirLcp = new Int32Array(nd);
    let maxDirLen = 0;
    for (let d = 0; d < nd; d++) {
      const s = this.dirOff[d];
      const e = this.dirOff[d + 1];
      if (e - s > maxDirLen) maxDirLen = e - s;
      if (d === 0) continue;
      const ps = this.dirOff[d - 1];
      const limit = Math.min(s - ps, e - s);
      let l = 0;
      while (l < limit && dirBuf[ps + l] === dirBuf[s + l]) l++;
      this.dirLcp[d] = l;
    }

    /** Greedy match state at each byte offset of the directory being scanned. */
    this.kAt = new Int32Array(maxDirLen + 1);
    /** Per-directory query consumption, reused across keystrokes. */
    this.dmScratch = new Int32Array(nd);

    // Static rank: shallower and shorter wins. Breaks ties between equally
    // good matches, and makes the prefix channel's top-K an integer compare.
    this.staticRank = new Int32Array(n);
    for (let i = 0; i < n; i++) {
      const d = dirId[i];
      const ds = this.dirOff[d];
      const de = this.dirOff[d + 1];
      let depth = de > ds ? 1 : 0;
      for (let j = ds; j < de; j++) {
        if (dirBuf[j] === SLASH) depth++;
      }
      this.staticRank[i] = -(de - ds + (baseOff[i + 1] - baseOff[i]) + depth * 12);
    }

    this.byBase = buildBasenameOrder(this);
    this.decoder = new TextDecoder();
    this.reset();
  }

  reset() {
    /** Query as lowercase UTF-8 bytes. */
    this.q = [];
    /**
     * The same query, exactly as typed. The index is ordered by raw bytes, so
     * the literal prefix range has to be searched for in those terms, not in
     * folded ones.
     */
    this.qRaw = EMPTY;
    this.qmask = 0;
    // cand[t] holds files whose basename contains the first t query bytes as a
    // subsequence. cand[0] is null, meaning "everything".
    this.cand = [null];
    // dcand[t] is the same for the full path, but populated lazily: only query
    // lengths that actually reached channel D have an entry.
    this.dcand = [null];
    this.scratch = new Int32Array(this.n);
  }

  cmpPrefix(i, q) {
    let x = this.baseOff[i];
    const xe = this.baseOff[i + 1];
    const buf = this.baseBuf;
    for (let k = 0; k < q.length; k++) {
      if (x >= xe) return -1;
      const d = LOW[buf[x++]] - q[k];
      if (d !== 0) return d < 0 ? -1 : 1;
    }
    return 0;
  }

  /** Half-open range of `byBase` whose basenames start with `q`. */
  prefixRange(q) {
    let lo = 0;
    let hi = this.n;
    while (lo < hi) {
      const m = (lo + hi) >> 1;
      if (this.cmpPrefix(this.byBase[m], q) < 0) lo = m + 1;
      else hi = m;
    }
    const start = lo;
    hi = this.n;
    while (lo < hi) {
      const m = (lo + hi) >> 1;
      if (this.cmpPrefix(this.byBase[m], q) <= 0) lo = m + 1;
      else hi = m;
    }
    return [start, lo];
  }

  /** True if `q[from..]` is a subsequence of basename `i`. */
  baseMatchesFrom(i, from) {
    const q = this.q;
    const ql = q.length;
    if (from >= ql) return true;
    const buf = this.baseBuf;
    const e = this.baseOff[i + 1];
    let k = from;
    for (let j = this.baseOff[i]; j < e; j++) {
      if (LOW[buf[j]] === q[k] && ++k === ql) return true;
    }
    return false;
  }

  /**
   * Advance the channel-C candidate stack by one byte.
   *
   * Subsequence matching is monotone under query extension, so this only ever
   * examines survivors of the previous keystroke; backspace is a pop.
   */
  narrow() {
    const prev = this.cand[this.cand.length - 1];
    const out = this.scratch;
    const mask = this.qmask;
    const baseMask = this.baseMask;
    let n = 0;
    if (prev === null) {
      for (let i = 0; i < this.n; i++) {
        if ((mask & ~baseMask[i]) !== 0) continue;
        if (this.baseMatchesFrom(i, 0)) out[n++] = i;
      }
    } else {
      for (let x = 0; x < prev.length; x++) {
        const i = prev[x];
        if ((mask & ~baseMask[i]) !== 0) continue;
        if (this.baseMatchesFrom(i, 0)) out[n++] = i;
      }
    }
    this.cand.push(out.slice(0, n));
  }

  /**
   * Sets the query, reusing whatever prefix of the candidate stack still
   * applies. Typing extends it; backspacing pops it.
   *
   * The query is compared as UTF-8 bytes. For non-ASCII input a byte-level
   * subsequence is a superset of a character-level one, so this can admit an
   * extra row but can never hide a matching path.
   */
  setQuery(text) {
    const next = ENC.encode(text);
    this.qRaw = next;
    let keep = 0;
    while (keep < next.length && keep < this.q.length && LOW[next[keep]] === this.q[keep]) {
      keep++;
    }
    while (this.q.length > keep) {
      this.q.pop();
      this.cand.pop();
    }
    // Entries deeper than the surviving prefix describe a query that is no
    // longer being asked.
    if (this.dcand.length > keep + 1) {
      this.dcand.length = keep + 1;
    }
    this.qmask = 0;
    for (const c of this.q) this.qmask |= 1 << CLS[c];
    for (let k = keep; k < next.length; k++) {
      const c = LOW[next[k]];
      this.q.push(c);
      this.qmask |= 1 << CLS[c];
      this.narrow();
    }
  }

  /** Byte length of the directory prefix, including its trailing slash. */
  shiftOf(i) {
    const d = this.dirId[i];
    const len = this.dirOff[d + 1] - this.dirOff[d];
    return len === 0 ? 0 : len + 1;
  }

  /** Reconstructs a full path. Called only for displayed rows. */
  pathOf(i) {
    const d = this.dirId[i];
    const ds = this.dirOff[d];
    const de = this.dirOff[d + 1];
    const base = this.decoder.decode(this.baseBuf.subarray(this.baseOff[i], this.baseOff[i + 1]));
    if (de === ds) return base;
    return this.decoder.decode(this.dirBuf.subarray(ds, de)) + '/' + base;
  }

  /**
   * Compares `bytes` against the path at `i`, as unsigned bytes.
   *
   * Walks the stored directory and basename in place rather than rebuilding
   * the path, so a lookup costs no allocation.
   */
  cmpPathAt(i, bytes) {
    const d = this.dirId[i];
    const ds = this.dirOff[d];
    const de = this.dirOff[d + 1];
    const bs = this.baseOff[i];
    const be = this.baseOff[i + 1];
    const dirLen = de - ds;
    const pathLen = dirLen === 0 ? be - bs : dirLen + 1 + (be - bs);
    const n = bytes.length < pathLen ? bytes.length : pathLen;
    for (let k = 0; k < n; k++) {
      const c = this.byteAt(i, k, ds, bs, dirLen);
      if (bytes[k] !== c) return bytes[k] - c;
    }
    return bytes.length - pathLen;
  }

  /**
   * Index of an exact path, or -1.
   *
   * The server emits paths in tree order, which for full paths is plain
   * byte-lexicographic ascending order; that was checked over all 506,237
   * paths of chromium/src, so a binary search is sound.
   */
  indexOfPath(path) {
    const bytes = ENC.encode(path);
    let lo = 0;
    let hi = this.n - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      const c = this.cmpPathAt(mid, bytes);
      if (c === 0) return mid;
      if (c < 0) hi = mid - 1;
      else lo = mid + 1;
    }
    return -1;
  }

  /**
   * Orders the path at `i` against `bytes` read as a prefix: negative if the
   * path sorts before it, zero if the path starts with it, positive after.
   *
   * Note the sign convention is path-minus-query, matching {@link cmpPrefix},
   * which does the same job for basenames -- and the opposite of {@link
   * cmpPathAt}, which answers a different question.
   */
  pathStartsCmp(i, bytes) {
    const d = this.dirId[i];
    const ds = this.dirOff[d];
    const de = this.dirOff[d + 1];
    const bs = this.baseOff[i];
    const dirLen = de - ds;
    const pathLen = dirLen === 0 ? this.baseOff[i + 1] - bs : dirLen + 1 + (this.baseOff[i + 1] - bs);
    // A path shorter than the query cannot start with it, and sorts before it.
    if (pathLen < bytes.length) {
      const n = pathLen;
      for (let k = 0; k < n; k++) {
        const c = this.byteAt(i, k, ds, bs, dirLen);
        if (c !== bytes[k]) return c - bytes[k];
      }
      return -1;
    }
    for (let k = 0; k < bytes.length; k++) {
      const c = this.byteAt(i, k, ds, bs, dirLen);
      if (c !== bytes[k]) return c - bytes[k];
    }
    return 0;
  }

  /** Byte `k` of path `i`, reading the stored directory and basename in place. */
  byteAt(i, k, ds, bs, dirLen) {
    if (dirLen === 0) return this.baseBuf[bs + k];
    if (k < dirLen) return this.dirBuf[ds + k];
    if (k === dirLen) return SLASH;
    return this.baseBuf[bs + k - dirLen - 1];
  }

  /**
   * Half-open range of the index whose full paths literally start with the
   * query as typed.
   *
   * Sound because the index is in byte order, so those paths are contiguous --
   * the same property {@link indexOfPath} relies on, and the reason a
   * caller-supplied list is sorted on the way in.
   *
   * Deliberately case-sensitive: the order is over raw bytes, and a
   * case-folded range would not be contiguous. Callers must therefore use this
   * only to promote, never to filter, so that a reader who types the wrong
   * case loses the promotion and not the result.
   */
  pathPrefixRange() {
    const bytes = this.qRaw;
    if (bytes.length === 0) return [0, 0];
    let lo = 0;
    let hi = this.n;
    while (lo < hi) {
      const m = (lo + hi) >> 1;
      if (this.pathStartsCmp(m, bytes) < 0) lo = m + 1;
      else hi = m;
    }
    const start = lo;
    hi = this.n;
    while (lo < hi) {
      const m = (lo + hi) >> 1;
      if (this.pathStartsCmp(m, bytes) <= 0) lo = m + 1;
      else hi = m;
    }
    return [start, lo];
  }
}

/**
 * Sorts file indices by basename. At half a million entries this costs roughly
 * a quarter of a second, once, in a worker.
 */
function buildBasenameOrder(ix) {
  const order = new Array(ix.n);
  for (let i = 0; i < ix.n; i++) order[i] = i;
  const buf = ix.baseBuf;
  const off = ix.baseOff;
  order.sort((a, b) => {
    let x = off[a];
    let y = off[b];
    const xe = off[a + 1];
    const ye = off[b + 1];
    while (x < xe && y < ye) {
      const d = LOW[buf[x++]] - LOW[buf[y++]];
      if (d !== 0) return d;
    }
    return xe - off[a] - (ye - off[b]);
  });
  return Int32Array.from(order);
}

// --------------------------------------------------------------- scoring

const BONUS_START = 40;
const BONUS_SEPARATOR = 30;
const BONUS_CAMEL = 25;
const BONUS_CONSECUTIVE = 20;
const PENALTY_GAP = 3;
const MAX_GAP_PENALTY = 30;

/**
 * Scratch for match offsets. Scoring runs on every candidate of every
 * keystroke -- hundreds of thousands of times -- so it must not allocate.
 * Basenames longer than this are still matched and scored, just without
 * highlight offsets past the limit.
 */
const POS = new Int32Array(256);
let posCount = 0;

/**
 * Scores how well `q[from..]` aligns inside basename `i`, recording match
 * offsets in `POS[0..posCount)` for highlighting.
 *
 * Two passes. A forward greedy match finds the earliest position at which the
 * query can finish; a backward greedy match from there yields the tightest
 * window, which is what a reader perceives as the real match. Forward-only
 * greedy binds each character as early as it can and routinely selects the
 * wrong occurrence -- for `gv` against `.../gitiles/GitilesView.java` it binds
 * `g` to the first `g` of `gitiles` rather than to `GitilesView`.
 *
 * @return {number} score, or -1 when there is no match.
 */
function scoreBasename(ix, i, from) {
  const q = ix.q;
  const ql = q.length;
  const buf = ix.baseBuf;
  const s = ix.baseOff[i];
  const e = ix.baseOff[i + 1];
  posCount = 0;
  if (from >= ql) return 0;

  let k = from;
  let end = -1;
  for (let j = s; j < e; j++) {
    if (LOW[buf[j]] === q[k] && ++k === ql) {
      end = j;
      break;
    }
  }
  if (end < 0) return -1;

  const count = ql - from;
  const capped = count <= POS.length ? count : POS.length;
  k = ql - 1;
  for (let j = end; j >= s; j--) {
    if (LOW[buf[j]] === q[k]) {
      const slot = k - from;
      if (slot < capped) POS[slot] = j - s;
      if (k-- === from) break;
    }
  }
  posCount = capped;

  let score = 0;
  let prev = -2;
  for (let t = 0; t < capped; t++) {
    const pos = POS[t];
    const abs = s + pos;
    if (pos === 0) {
      score += BONUS_START;
    } else {
      const before = buf[abs - 1];
      if (SEP[before]) score += BONUS_SEPARATOR;
      else if (isUpper(buf[abs]) && isLower(before)) score += BONUS_CAMEL;
    }
    if (pos === prev + 1) score += BONUS_CONSECUTIVE;
    else if (prev >= 0) score -= Math.min((pos - prev - 1) * PENALTY_GAP, MAX_GAP_PENALTY);
    prev = pos;
  }
  // Prefer covering more of a shorter name.
  score -= (e - s - count) >> 1;
  return score;
}

// --------------------------------------------------------------- top-K

/**
 * Keeps the best `limit` items in an insertion-sorted pair of typed arrays.
 *
 * Exact: an item is rejected only when it cannot beat the current k-th best,
 * and that threshold only ever rises. Candidates are offered in ascending
 * index order and ties do not displace, so ordering is stable.
 *
 * Allocation-free by construction. The naive version -- push an object per
 * candidate, sort at the end -- costs a hundred thousand short-lived objects
 * on a broad query, which dominated everything else in the profile.
 */
class TopK {
  constructor(limit) {
    this.limit = limit;
    this.score = new Float64Array(limit);
    this.id = new Int32Array(limit);
    this.size = 0;
  }

  offer(score, id) {
    const full = this.size === this.limit;
    if (full && score <= this.score[this.limit - 1]) return;
    let i = full ? this.limit - 1 : this.size++;
    while (i > 0 && this.score[i - 1] < score) {
      this.score[i] = this.score[i - 1];
      this.id[i] = this.id[i - 1];
      i--;
    }
    this.score[i] = score;
    this.id[i] = id;
  }

  ids() {
    return Array.from(this.id.subarray(0, this.size));
  }
}

// --------------------------------------------------------------- channels

/**
 * Greedy-maximal prefix of the query consumed by each directory, including the
 * separator that follows it.
 *
 * Greedy is optimal here: consuming as much of the query as possible in the
 * directory leaves the shortest remainder for the basename, and being a
 * subsequence is monotone in the remainder. So a file matches the full path if
 * and only if `q[dm[dir]..]` is a subsequence of its basename. Computing this
 * once per directory rather than once per file is the whole reason the
 * full-path channel is affordable.
 *
 * Each directory shares `lcp` bytes with the one before it, and the greedy
 * state after those bytes is by definition identical, so `kAt` records the
 * state at every offset of the directory just scanned and the next directory
 * resumes from its own shared length. Only differing tails are examined.
 *
 * The one wrinkle is saturation. A scan that consumes the whole query stops
 * early and leaves `kAt` unwritten past that point, so `sat` records where
 * that happened and any resume at or beyond it is saturated by definition.
 * Crucially `sat` must then be left alone: such a resume writes no `kAt` at
 * all, and since the directory agrees with its predecessor for `lcp >= sat`
 * bytes it saturates at the very same offset. Advancing `sat` to `lcp` would
 * claim a range of `kAt` as valid that no scan has written since, and a later
 * directory with a smaller `lcp` would resume from a stale state and
 * under-count its match. Holding `sat` fixed keeps it equal to the highest
 * offset `kAt` is valid at, so every read is either a written entry or a
 * saturated one.
 */
function dirPrefixLens(ix) {
  const q = ix.q;
  const ql = q.length;
  const dm = ix.dmScratch;
  const kAt = ix.kAt;
  const buf = ix.dirBuf;
  const off = ix.dirOff;
  const lcp = ix.dirLcp;
  const nd = ix.d;
  let sat = 0x7fffffff;
  kAt[0] = 0;
  for (let d = 0; d < nd; d++) {
    const s = off[d];
    const len = off[d + 1] - s;
    const l = lcp[d];
    let k;
    if (l >= sat) {
      k = ql;
    } else {
      k = kAt[l];
      let j = l;
      for (; j < len && k < ql; j++) {
        if (LOW[buf[s + j]] === q[k]) k++;
        kAt[j + 1] = k;
      }
      sat = k === ql ? j : 0x7fffffff;
    }
    dm[d] = len > 0 && k < ql && q[k] === SLASH ? k + 1 : k;
  }
  return dm;
}

/** Merges basename-relative match offsets into full-path highlight ranges. */
function rangesOf(shift) {
  const ranges = [];
  for (let t = 0; t < posCount; t++) {
    const p = POS[t] + shift;
    const last = ranges.length ? ranges[ranges.length - 1] : null;
    if (last && last[0] + last[1] === p) last[1]++;
    else ranges.push([p, 1]);
  }
  return ranges;
}

/**
 * Narrows the channel-D candidate set to the current query.
 *
 * Full-path subsequence matching is monotone under query extension, exactly as
 * basename matching is, so this reuses the deepest previously computed set
 * rather than rescanning the corpus. That matters because channel D engages
 * for a whole path-directed query -- "base/task/thread" reaches it at the
 * fifth keystroke and stays there -- and a full scan on each of the remaining
 * keystrokes is what made this the slowest path in the engine.
 *
 * Filtering directly with the complete query rather than one byte at a time is
 * valid for the same reason: matches of the longer query are a subset.
 */
function narrowD(ix, dm) {
  const q = ix.q;
  const ql = q.length;
  let t = ql;
  while (t > 0 && ix.dcand[t] === undefined) t--;
  const start = ix.dcand[t];

  // sufMask[k] is the 1-gram mask of q[k..]. A file can only match if its
  // basename *alone* contains every character the directory failed to consume.
  // That is strictly sharper than asking whether the directory and basename
  // together contain the whole query, because directory characters cannot
  // discharge an obligation that falls on the basename: on "base/task/thread"
  // it cuts the files needing a subsequence scan from 239,228 to 553.
  const sufMask = new Int32Array(ql + 1);
  for (let k = ql - 1; k >= 0; k--) {
    sufMask[k] = sufMask[k + 1] | (1 << CLS[q[k]]);
  }

  const out = ix.scratch;
  const baseMask = ix.baseMask;
  const dirId = ix.dirId;
  let n = 0;

  if (start === null || start === undefined) {
    const dirEnd = ix.dirEnd;
    const nd = ix.d;
    let i = 0;
    for (let d = 0; d < nd; d++) {
      const end = dirEnd[d];
      const from = dm[d];
      if (from >= ql) {
        // The directory alone consumed the query, so every file beneath it
        // matches and none of them needs testing.
        while (i < end) out[n++] = i++;
        continue;
      }
      const need = sufMask[from];
      while (i < end) {
        if ((need & ~baseMask[i]) === 0 && ix.baseMatchesFrom(i, from)) out[n++] = i;
        i++;
      }
    }
  } else {
    for (let x = 0; x < start.length; x++) {
      const i = start[x];
      const from = dm[dirId[i]];
      if ((sufMask[from] & ~baseMask[i]) !== 0) continue;
      if (ix.baseMatchesFrom(i, from)) out[n++] = i;
    }
  }
  const result = out.slice(0, n);
  ix.dcand[ql] = result;
  return result;
}

/**
 * Runs the ranked channels in cost order.
 *
 *   A  literal prefix of the whole path  -- two binary searches
 *   B  exact and prefix on the basename  -- two binary searches
 *   C  subsequence on the basename       -- scan of the narrowed candidate set
 *   D  subsequence on the full path      -- dir-memoised, monotonically narrowed
 *
 * A channel is skipped only when a cheaper one already filled the display.
 * That is exact rather than heuristic, because the ranking policy is a strict
 * tiering: a path the reader has literally spelled the start of outranks
 * everything, then a basename prefix outranks a non-prefix, and a basename
 * match outranks a match that needed the directory. Nothing a later channel
 * produces can displace what an earlier one found.
 *
 * A exists because B, C and D all score the *basename* alone. When the
 * directory has already absorbed the whole query -- which is exactly what
 * happens for "base/task" or "device/" -- scoreBasename returns 0, so a
 * perfect prefix match scored zero and sank below any path where the basename
 * happened to contribute. Typing a path got monotonically worse results the
 * more of it you spelled, which is the opposite of what a finder is for.
 *
 * D is a superset of both A and C, so when D runs its count is the
 * authoritative total.
 */
function search(ix) {
  const q = ix.q;

  if (q.length === 0) {
    const top = new TopK(LIMIT);
    for (let i = 0; i < ix.n; i++) top.offer(ix.staticRank[i], i);
    return {
      total: ix.n,
      exact: true,
      items: top.ids().map((id) => ({path: ix.pathOf(id), ranges: []})),
    };
  }

  // ---- channel A
  const [as, ae] = ix.pathPrefixRange();
  const pathPrefixCount = ae - as;
  const headroom = LIMIT - pathPrefixCount;
  const head = [];
  if (pathPrefixCount > 0) {
    const top = new TopK(pathPrefixCount < LIMIT ? pathPrefixCount : LIMIT);
    for (let i = as; i < ae; i++) top.offer(ix.staticRank[i], i);
    for (const id of top.ids()) {
      head.push({path: ix.pathOf(id), ranges: [[0, ix.qRaw.length]]});
    }
    if (headroom <= 0) {
      // The count is a lower bound: other paths may match without starting
      // with the query. The display says so with a trailing "+".
      return {total: pathPrefixCount, exact: false, items: head};
    }
  }

  // Anything already promoted must not appear twice. Membership is a range
  // test because the index is in byte order.
  const promoted = (i) => i >= as && i < ae;

  // ---- channel B
  //
  // The gate allows for every promoted path also turning up here, so the
  // channel can never be chosen and then come up short of a full display.
  // Being conservative only costs a fall-through to C or D, which are
  // supersets of it.
  const [ps, pe] = ix.prefixRange(q);
  const prefixCount = pe - ps;
  if (prefixCount >= headroom + pathPrefixCount) {
    const top = new TopK(headroom);
    for (let m = ps; m < pe; m++) {
      const i = ix.byBase[m];
      if (promoted(i)) continue;
      const exact = ix.baseOff[i + 1] - ix.baseOff[i] === q.length;
      top.offer(ix.staticRank[i] + (exact ? 1 << 20 : 0), i);
    }
    const items = head.concat(
      top.ids().map((id) => ({
        path: ix.pathOf(id),
        ranges: [[ix.shiftOf(id), q.length]],
      })),
    );
    return {total: Math.max(prefixCount, items.length), exact: false, items};
  }

  // ---- channel C
  const cand = ix.cand[ix.cand.length - 1];
  const count = cand === null ? ix.n : cand.length;
  if (count >= headroom + pathPrefixCount) {
    const top = new TopK(headroom);
    for (let x = 0; x < count; x++) {
      const i = cand === null ? x : cand[x];
      if (promoted(i)) continue;
      const sc = scoreBasename(ix, i, 0);
      if (sc < 0) continue;
      top.offer(sc * 64 + (ix.staticRank[i] >> 4), i);
    }
    const items = head.concat(
      top.ids().map((id) => {
        scoreBasename(ix, id, 0);
        return {path: ix.pathOf(id), ranges: rangesOf(ix.shiftOf(id))};
      }),
    );
    return {total: Math.max(count, items.length), exact: false, items};
  }

  // ---- channel D
  //
  // Reached when the cheaper channels cannot fill the display, which is the
  // normal case for a path-directed query such as "base/task/thread". Without
  // it the finder would report no matches for files that plainly exist.
  const dm = dirPrefixLens(ix);
  const matches = narrowD(ix, dm);
  const top = new TopK(headroom);
  for (let x = 0; x < matches.length; x++) {
    const i = matches[x];
    if (promoted(i)) continue;
    const from = dm[ix.dirId[i]];
    const sc = scoreBasename(ix, i, from);
    // A match that needed the directory ranks below any basename-only match.
    top.offer(sc * 64 + (ix.staticRank[i] >> 4) - (from > 0 ? 1 << 18 : 0), i);
  }
  const items = head.concat(
    top.ids().map((id) => {
      scoreBasename(ix, id, dm[ix.dirId[id]]);
      return {path: ix.pathOf(id), ranges: rangesOf(ix.shiftOf(id))};
    }),
  );
  // Every path that starts with the query also contains it as a subsequence,
  // so D has already counted what A promoted.
  return {total: matches.length, exact: true, items};
}

// --------------------------------------------------------------- messages

self.onmessage = async (ev) => {
  const msg = ev.data;
  try {
    if (msg.type === 'load') {
      const res = await fetch(msg.url, {credentials: 'same-origin'});
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const text = await res.text();
      // Gitiles prefixes JSON responses with an anti-XSSI guard.
      const body = JSON.parse(text.replace(/^\)\]\}'\n/, ''));
      index = new Index(body.paths || []);
      self.postMessage({type: 'ready', total: index.n});
      return;
    }
    if (msg.type === 'query') {
      if (index === null) return;
      index.setQuery(msg.q);
      const {total, exact, items} = search(index);
      self.postMessage({
        type: 'results',
        seq: msg.seq,
        q: msg.q,
        total,
        exact,
        items,
      });
      return;
    }
    if (msg.type === 'recent') {
      // Remembered paths are only offered if they still exist at this
      // revision: a file deleted or renamed since it was last opened would
      // otherwise be presented as a result that 404s on Enter. Order is the
      // caller's, so ranking stays entirely in the UI where the scores live.
      if (index === null) return;
      const items = [];
      for (let i = 0; i < msg.paths.length; i++) {
        if (index.indexOfPath(msg.paths[i]) >= 0) {
          items.push({path: msg.paths[i], ranges: []});
        }
      }
      self.postMessage({
        type: 'results',
        seq: msg.seq,
        q: '',
        total: items.length,
        exact: true,
        recent: true,
        items,
      });
      return;
    }
  } catch (e) {
    self.postMessage({type: 'error', message: String(e && e.message ? e.message : e)});
  }
};

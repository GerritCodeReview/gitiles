# Gitiles API Reference

[TOC]

### Formatting

`?format=`: `TEXT`, `HTML`, and `JSON`

The `JSON` output has a `)]}'` line at the top to prevent cross-site scripting.
When parsing, strip that line and the rest can be parsed normally.

#### URL Format
Depending on the servlet context path set up to serve gitiles, the URL used to access these end points may differ.

On gerrit.googlesource.com one URL is: `https://gerrit.googlesource.com/a/gitiles/+refs?format=TEXT`, while for a standard localhost deployment, the equivalent URL would be `http://localhost:8080/a/plugins/gitiles/test+refs?format=TEXT`. The examples given in this document are of the former form, to allow testing against real refs. They require authentication described in [the REST API Developer's Notes](https://gerrit-review.googlesource.com/Documentation/dev-rest-api.html).

### Endpoints

#### **`+refs`**
`https://gerrit.googlesource.com/a/gitiles/+refs?format=TEXT`

Lists all refs (branches, tags, etc.) in the repository.

#### **`+log`**
`https://gerrit.googlesource.com/a/gitiles/+log/refs/heads/master?n=10&format=JSON`

Shows the commit log.

##### Filter parameters
* `author=<name or email>`
* `committer=<name or email>`
* `grep=<pattern>`

##### Limit and Paging parameters
* `n=<number>` to limit the number of commits returned.
* `s=<start_number>` to set start during paging.
   The `next` key in the JSON response provides a cursor for the next page.
   Use it with `s=<next_cursor>`.
   The final page will have no `next` key.
   Every page except for the first will have a `previous` cursor to page backwards.

#### **`+grep`**
`https://gerrit.googlesource.com/a/gitiles/+grep/refs/heads/master?s=text&format=JSON`

Searches file contents at a revision.

##### Search parameters
* `s=<text>` lists matches containing the case-sensitive literal substring
  `<text>`. No search-syntax escaping is required. When constructing the URL
  directly, the value must use standard URL encoding.

A file or directory path may be supplied after the revision to limit the search.
The search is a case-sensitive literal substring search. Binary files and
blobs larger than 1 MiB are skipped. Results are limited to 1000 matches.

#### **`paths_only`**
`https://gerrit.googlesource.com/a/gitiles/+/refs/heads/master/?format=JSON&recursive=1&paths_only=1`

A compact projection of the recursive tree listing that returns only blob path
names, omitting the per-entry mode, type and object ID.

```json
{
  "id": "<tree sha>",
  "paths": ["Documentation/api-reference.md", "java/com/google/gitiles/PathServlet.java"]
}
```

* Requires `recursive=1` and cannot be combined with `long=1`; violating either
  fails with `400`. As with any recursive listing, a target that is not a tree
  fails with `404`.
* The listing is always complete. There is no bound on the number of paths
  returned and no truncation flag, so an absent path means the path does not
  exist at that revision.

The projection is substantially cheaper than the full recursive listing it is
derived from. For `chromium/src` at 506,237 blobs, `?recursive=1` transfers
17.1 MB gzipped while `?recursive=1&paths_only=1` transfers 3.4 MB.

> Note the trailing slash after the revision. `+/<revision>` without it
> addresses the revision itself rather than its root tree.

Requesting this by resolved commit SHA rather than by branch name makes the
response cacheable, since Gitiles only sends caching headers for revisions
named by object ID.


#### **`+show`**
`https://gerrit.googlesource.com/a/gitiles/+show/refs/heads/master/?format=JSON`

View the metadata about a given target. If the target is a file, use `format=TEXT` to view base64-encoded content, e.g.
```bash
curl "https://gerrit.googlesource.com/a/gitiles/+show/refs/heads/master/README.md?format=TEXT" | base64 -d
```

#### **`+archive`**
`https://gerrit.googlesource.com/a/gitiles/+archive/refs/heads/master.tar.gz`

Download a compressed archive of a repository at a specific commit or ref.
Supported formats: `.tar.gz` and `.zip`
```bash
curl "https://gerrit.googlesource.com/a/gitiles/+archive/refs/heads/master.tar.gz" -o repo.tar.gz
curl "https://gerrit.googlesource.com/a/gitiles/+archive/30851aacbea3370c7be8179c890b3401526242eb.tar.gz" -o repo.tar.gz
```

Download a compressed archive of a folder `java/com/google/gitiles/dev/` in a repository at a specific commit or ref.
```bash
curl "https://gerrit.googlesource.com/a/gitiles/+/refs/heads/master/java/com/google/gitiles/dev.tar.gz" -o dev.tar.gz
curl "https://gerrit.googlesource.com/a/gitiles/+/30851aacbea3370c7be8179c890b3401526242eb/java/com/google/gitiles/dev.tar.gz" -o dev.tar.gz
```

#### **`+doc`**
`https://gerrit.googlesource.com/a/gitiles/+doc/refs/heads/master/README.md`

Renders Markdown files into HTML.

#### **`+blame`**
`https://gerrit.googlesource.com/a/gitiles/+blame/refs/heads/master/README.md`

Shows line-by-line author information for a specific file (`git blame`).

#### **`+diff`**
`https://gerrit.googlesource.com/a/gitiles/+diff/refs/heads/master/?from=master~1&to=master`

Compute the diff between two commits.

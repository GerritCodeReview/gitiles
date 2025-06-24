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
Use the parameter `n=<number>` to limit the number of commits returned.
For paging use the start parameter `s=<next_cursor>`.
The `next` key in the JSON provides a cursor for the next page. Use it with `s=<next_cursor>`.
The final page will have no `next` key.
Every page except for the first will have a `previous` cursor to page backwards.

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
curl "https://gerrit.googlesource.com/a/gitiles/+/refs/heads/master/java/com/google/gitiles/dev/" -o dev.tar.gz
curl "https://gerrit.googlesource.com/a/gitiles/+/30851aacbea3370c7be8179c890b3401526242eb/java/com/google/gitiles/dev/" -o dev.tar.gz
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
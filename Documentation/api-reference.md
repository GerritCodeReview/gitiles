# Gitiles API Reference

[TOC]

### Formatting

- `?format=`: `TEXT`, `HTML`, and `JSON`
- The `JSON` output has a `)]}'` line at the top to prevent cross-site scripting. When parsing, strip that line and the rest can be parsed normally.

### Endpoints

#### **`+refs`**
`https://gerrit.googlesource.com/a/gitiles/+refs?format=TEXT`
   - Lists all refs (branches, tags, etc.) in the repository.

#### **`+log`**
`https://gerrit.googlesource.com/a/gitiles/+log/refs/heads/master?n=10&format=JSON`
   - Shows the commit log
   - Use `n=<number>` to limit the number of commits returned.
   - Use `s=<next_cursor>` to set the start parameter.
   - The `next` key in the JSON provides a cursor for the next page. Use it with `s=<next_cursor>`.
   - The last page will have no `next` key.
   - Any page except for the first will have a `previous` cursor to page backwards.

#### **`+show`**
`https://gerrit.googlesource.com/a/gitiles/+show/refs/heads/master/?format=JSON`
   - View the metadata about a given target.
   - If the target is a file, use `format=TEXT` to view base64-encoded content
   ```bash
   curl -n "https://gerrit.googlesource.com/a/gitiles/+show/refs/heads/master/README.md?format=TEXT" | base64 -d
   ```

#### **`+archive`**
   - Download a compressed archive of a repository at a specific commit or ref.
   - Supported formats: `.tar.gz` and `.zip`
   ```bash
   curl -n "https://gerrit.googlesource.com/a/gitiles/+archive/refs/heads/master.tar.gz" -o repo.tar.gz
   ```

#### **`+doc`**
`https://gerrit.googlesource.com/a/gitiles/+doc/refs/heads/master/README.md`
   - Renders Markdown files into HTML

#### **`+blame`**
`https://gerrit.googlesource.com/a/gitiles/+blame/refs/heads/master/README.md`
   - Shows line-by-line author information for a specific file

#### **`+diff`**
`https://gerrit.googlesource.com/a/gitiles/+diff/refs/heads/master/?from=master~1&to=master`
   - Diff two refs
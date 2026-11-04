# Configuration

The `gitiles.config` file supporting the site contains several configuration
options.

[TOC]

## Core configuration

### Site Title

The title of the site. Default: `Gitiles`.

```
[gitiles]
  siteTitle = Acme Inc. Git Browser
```

### Base Path

The file system path gitiles will scan for git repositories to serve when started
in dev mode using `./tools/dev_run.sh`. By default the current working directory
where the gitiles process was started from.

```
[gitiles]
  basePath = /git/
```

### URLs

`canonicalHostName`.
Default: `null`, determine the hostname from the local host.

`baseGitUrl` the base URL for git repositories.

`gerritUrl`, URL prefix that when set is used to create:
* Links from the `Change-Id` headers in commit messages to the corresponding
  Gerrit change.
* An edit link in file views that opens a new change in Gerrit in online edit
  mode for that file. The link will only appear when you are browsing the file
  at a branch, so the commitish must start with `refs/heads/`.

`contextPath` the base path for the gitiles urls. Only applicable for dev mode started using `./tools/dev_run.sh`.

> If you are using the Gerrit Gitiles plugin, this is set based on Gerrit's configuration.
> Default: `null`, do not link `Change-Id` or show edit links.

```
[gitiles]
  canonicalHostName = gitiles.example.org
  gerritUrl = https://gerrit.example.org/r/
  baseGitUrl = git://git.example.org/
  contextPath = /additional/basepath/for/gitiles/
```

### Repositories export

Set `exportAll` to instruct jGit to export all repositories, ignoring the check
for existence of `git-daemon-export-ok` file at the root of the repository.

Default: `false`, repositories need to be explicitly marked for export.

```
[gitiles]
exportAll = true
```

### Template engine

Gitiles supports two HTML template engines:

* `thymeleaf`: [Thymeleaf 3.1](https://www.thymeleaf.org/) HTML5 templates
  (`resources/com/google/gitiles/templates/*.html`). **Recommended.** Google is
  discontinuing open-source maintenance of Closure Templates (Soy), and
  Thymeleaf is the actively maintained replacement engine.
* `soy`: [Closure Templates (Soy)](https://github.com/google/closure-templates)
  (`resources/com/google/gitiles/templates/*.soy`). **Default** for backward
  compatibility, but deprecated and may be removed in a future release.

Set `renderer` under `[gitiles]` in `gitiles.config` to choose the default
template engine:

```
[gitiles]
  renderer = thymeleaf
```

To explicitly keep the legacy Soy template engine:

```
[gitiles]
  renderer = soy
```

#### Programmatic configuration (Java API & plugins)

Applications and Gerrit plugins embedding Gitiles can configure or register
template engines via `DefaultRenderer`, `DebugRenderer`, or `RendererFactory`:

1. **Using `DefaultRenderer` / `DebugRenderer` with a renderer type (`String`)
   or selector (`Supplier<String>`):**

   ```java
   // Select engine from gitiles.config ([gitiles] renderer = thymeleaf | soy):
   Renderer renderer =
       new DefaultRenderer(
           staticPrefix,
           customTemplates,
           siteTitle,
           config.getString("gitiles", null, "renderer"));

   // Or supply globals and a renderer type selector:
   Renderer renderer =
       new DefaultRenderer(
           globals, staticPrefix, customTemplates, siteTitle, () -> "thymeleaf");
   ```

2. **Direct factory instantiation or registering a custom `Renderer`
   implementation (`RendererFactory`):**

   ```java
   // Instantiate the configured renderer via RendererFactory:
   Renderer renderer =
       RendererFactory.createDefaultRenderer(
           config.getString("gitiles", null, "renderer"),
           globals,
           staticPrefix,
           customTemplates,
           siteTitle);

   // Register an additional Renderer implementation (e.g. from a plugin):
   RendererFactory.register(
       "custom-engine",
       (globals, staticPrefix, customTemplates, siteTitle) -> ...,
       (staticPrefix, customTemplatesFilenames, templatesRoot, siteTitle) -> ...);
   ```

### Custom templating

Gitiles allows injecting custom template files via `customTemplates` and
activating them with `[template] customVariant`.

#### Thymeleaf custom templates (`.html`)

When using `renderer = thymeleaf`, provide one or more HTML template files
containing Thymeleaf `th:fragment` definitions:

```
[gitiles]
  renderer = thymeleaf
  customTemplates = path/to/custom.html
[template]
  customVariant = custom
```

> **Migration tip:** If `customTemplates` in `gitiles.config` points to a
> `.soy` file (e.g. `path/to/custom.soy`), `ThymeleafRenderer` automatically
> looks for a sibling `.html` file at the same path (`path/to/custom.html`).
> This allows you to keep a single `gitiles.config` while testing both engines
> side by side.

The following Thymeleaf fragments can be defined in your custom `.html` file
(referenced via the `CustomTemplates` template namespace when `customVariant`
is set):

* `th:fragment="customHeadTagPart"` &mdash; injected inside `<head>` on both
  repository views (`Common.html`) and Markdown documentation pages
  (`Doc.html`).
* `th:fragment="customHeader"` &mdash; replaces the default `<div
  class="Header-title">` site header in `Common.html`.
* `th:fragment="customFooter"` &mdash; replaces the default `<footer
  class="Site-footer">` on repository views in `Common.html`.
* `th:fragment="customDocFooter(sourceUrl, logUrl, blameUrl)"` &mdash; replaces
  the default `<footer class="Site-footer">` on Markdown documentation pages
  (`Doc.html`).

Example `custom.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <th:block th:fragment="customHeadTagPart">
    <link rel="stylesheet" type="text/css" href="/static/custom.css">
  </th:block>

  <div th:fragment="customHeader" class="Header-title">
    <a href="/" th:text="${SITE_TITLE}">Acme Git</a>
  </div>

  <footer th:fragment="customFooter" class="Site-footer">
    <div class="Footer">
      <th:block th:replace="~{Common :: footerPoweredBy}"></th:block>
      <th:block th:replace="~{Common :: footerFormatBadge}"></th:block>
    </div>
  </footer>

  <footer th:fragment="customDocFooter(sourceUrl, logUrl, blameUrl)" class="Site-footer">
    <div class="Footer">
      <th:block th:replace="~{Common :: footerPoweredBy}"></th:block>
      <div class="Footer-links">
        <a th:if="${sourceUrl != null and sourceUrl != ''}" class="Footer-link" th:href="${sourceUrl}">source</a>
        <a th:if="${logUrl != null and logUrl != ''}" class="Footer-link" th:href="${logUrl}">log</a>
        <a th:if="${blameUrl != null and blameUrl != ''}" class="Footer-link" th:href="${blameUrl}">blame</a>
      </div>
    </div>
  </footer>
</body>
</html>
```

#### Soy custom templates (`.soy`, deprecated)

When using `renderer = soy`, web views are defined via Soy `{deltemplate}`
overrides. See `.soy` files in `resources/com/google/gitiles/templates/` for
available delegate templates:

```
[gitiles]
  renderer = soy
  customTemplates = path/to/somefile.soy
  customTemplates = path/to/another.soy
```

### Custom styling

It's possible to inject custom styles within the template using
`customHeadTagPart`.

#### With Thymeleaf (`renderer = thymeleaf`)

Add the following to `gitiles.config`:

```
[gitiles]
  renderer = thymeleaf
  customTemplates = path/to/custom.html
[template]
  customVariant = aVariant
```

Then define `custom.html` as follows:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
  <th:block th:fragment="customHeadTagPart">
    <link rel="stylesheet" type="text/css" href="/static/file.css">
  </th:block>
</body>
</html>
```

#### With Soy (`renderer = soy`)

Add the following to `gitiles.config`:
```
[gitiles]
    customTemplates = path/to/afile.soy
[template]
    customVariant=aVariant
```
Where `customVariant` is the variable defined in the default
templates themselves. Then you can define `afile.soy` as follows:
```
{namespace gitiles}
{deltemplate gitiles.customHeadTagPart variant="'aVariant'"}

<link rel="stylesheet" type="text/css" href="/static/file.css" />

{/deltemplate}
```
It is also possible to define a `<style>` tag there if desired.

### Fixed time zone

By default dates are formatted including the local user time zone as that
matches git tools. Some users/administrators may prefer normalizing to a
particular timezone so times are directly comparable without doing timezone
arithmetic.

Setting `fixedTimeZone` to a valid Java TimeZone ID causes all dates in the UI
to be implicitly converted to this timezone, and the now-redundant timezone
offset to be dropped from the output.

```
[gitiles]
  fixedTimeZone = UTC
```

### Cross-Origin Resource Sharing (CORS)

Gitiles sets the `Access-Control-Allow-Origin` header to the
HTTP origin of the client if the client's domain matches a regular
expression defined in `allowOriginRegex`.

```
[gitiles]
  allowOriginRegex = http://localhost
```

By default `allowOriginRegex` is unset, denying all cross-origin requests.

### Gitweb redirector

Redirect requests to old gitweb URLs after having migrated to Gitiles. Matching
URLs will be redirected permanently to their Gitiles equivalent.
Default: `true`.

```
[gitiles]
redirectGitweb = false
```

### File finder

Serve the keyboard-driven finder, opened with `/` to search files on any page
that names a repository and repositories on the host index. Default: `true`.

The finder is inert until a reader presses `/`; nothing is fetched and no index
is built before that. When it is opened on a repository page, the browser
downloads the revision's complete path listing and builds an index over it, both
linear in the number of paths and deliberately uncapped, so that a file which
exists is always findable. On the host index it indexes the repositories already
rendered on the page without an extra request. Set this to `false` to disable
both.

```
[gitiles]
fileSearch = false
```

## Markdown

### Disabling markdown

Markdown can be completely disabled by setting render to false.

```
[markdown]
  render = false
```

### Markdown size

Markdown files are limited by default to 5 MiB of input text
per file. This limit is configurable, but should not be raised
beyond available memory.

```
[markdown]
  inputLimit = 5M
```

### Image size

Referenced [images are inlined](#Images) as base64 encoded URIs.
The image limit places an upper bound on the byte size of input.

```
[markdown]
  imageLimit = 256K
```

### Extensions

The following extensions can be enabled/disabled in the markdown
section:

* `githubFlavor`: enable extensions that mirror GitHub Flavor
  Markdown behavior.  Default is true.

* `autolink`: automatically convert plain URLs and email
  addresses into links. Default follows `githubFlavor`.

* `blocknote`: Gitiles style note/promo/aside blocks to raise
  awareness to important content. Default false.

* `frontmatter`: strip a leading YAML front matter block (delimited
  by `---` lines) from the rendered output instead of treating it as
  document content. Default false.

* `ghthematicbreak`: accept `--` for `<hr>`, like GitHub Flavor
  Markdown.  Default follows `githubFlavor`.

* `multicolumn`: Gitiles extension to layout content in a 12 cell
   grid, delinated by section headers. Default false.

* `namedanchor`: Gitiles extension to extract named anchors using
  `#{id}` syntax. Default false.

* `safehtml`: Gitiles extension to accept very limited HTML; for
   security reasons all other HTML is dropped regardless of this
   setting.  Default follows `githubFlavor`.

* `smartquote`: Gitiles extension to convert single and double quote
  ASCII characters to Unicode smart quotes when in prose.  Default
  false.

* `strikethrough`: strikethrough text with GitHub Flavor Markdown
  style `~~`.  Default follows `githubFlavor`.

* `tables`: format tables with GitHub Flavor Markdown.  Default
  follows `githubFlavor`.

* `toc`: Gitiles extension to replace `[TOC]` in a paragraph by itself
  with a server-side generated table of contents extracted from section
  headers.  Default true.

### IFrames

IFrame support requires `markdown.safehtml` to be true.

IFrame source URLs can be whitelisted by providing a list of allowed
URLs. URLs ending with a `/` are treated as prefixes, allowing any source
URL beginning with that prefix.

```
[markdown]
  allowiframe = https://google.com/
```

URLs not ending with a `/` are treated as exact matches, and only those
source URLs will be allowed.


```
[markdown]
  allowiframe = https://example.com
  allowiframe = https://example.org
```

If the list has a single entry with the value `true`, all source URLs
will be allowed.


```
[markdown]
  allowiframe = true
```

## Google Analytics

[Google Analytics](https://www.google.com/analytics/) can be
enabled on every rendered markdown page by adding the Property ID
to the configuration file:

```
[google]
  analyticsId = UA-XXXX-Y
```

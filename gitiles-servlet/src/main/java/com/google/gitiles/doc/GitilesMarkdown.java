// Copyright 2015 Google Inc. All Rights Reserved.
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

package com.google.gitiles.doc;

import org.parboiled.Rule;
import org.pegdown.Parser;
import org.pegdown.plugins.BlockPluginParser;

/**
 * Additional markdown extensions known to Gitiles.
 * <p>
 * {@code [TOC]} as a stand-alone block will insert a table of contents
 * for the current document.
 */
class GitilesMarkdown extends Parser implements BlockPluginParser {
  GitilesMarkdown() {
    super(MarkdownHelper.MD_OPTIONS, 2000L, DefaultParseRunnerProvider);
  }

  @Override
  public Rule[] blockPluginRules() {
    return new Rule[]{ toc() };
  }

  public Rule toc() {
    return NodeSequence(
        string("[TOC]"),
        push(new TocNode()));
  }
}

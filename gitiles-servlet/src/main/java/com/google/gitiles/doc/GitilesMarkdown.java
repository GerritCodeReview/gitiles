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

import org.parboiled.BaseParser;
import org.parboiled.Rule;
import org.parboiled.support.StringBuilderVar;
import org.pegdown.Parser;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.RootNode;
import org.pegdown.plugins.BlockPluginParser;

/**
 * Additional markdown extensions known to Gitiles.
 * <p>
 * {@code [TOC]} as a stand-alone block will insert a table of contents
 * for the current document.
 */
class GitilesMarkdown extends Parser implements BlockPluginParser {
  private final PegDownProcessor parser;

  GitilesMarkdown() {
    super(MarkdownHelper.MD_OPTIONS, 2000L, DefaultParseRunnerProvider);
    parser = new PegDownProcessor(MarkdownHelper.MD_OPTIONS);
  }

  @Override
  public Rule[] blockPluginRules() {
    return new Rule[]{
        toc(),
        note(),
    };
  }

  public Rule toc() {
    return NodeSequence(
        string("[TOC]"),
        push(new TocNode()));
  }

  public Rule note() {
    StringBuilderVar body = new StringBuilderVar();
    return NodeSequence(
        string("***"), whitespace(), typeOfNote(), Newline(),
        oneOrMore(
          testNot(string("***"), Newline()),
          BaseParser.ANY,
          body.append(matchedChar())),
        string("***"), Newline(),
        push(new DivNode(
            (DivNode.Style) pop(),
            parse(body).getChildren())));
  }

  public Rule typeOfNote() {
    return firstOf(
        sequence(string("note"), push(DivNode.Style.NOTE)),
        sequence(string("promo"), push(DivNode.Style.PROMO)),
        sequence(string("aside"), push(DivNode.Style.ASIDE)));
  }

  public Rule whitespace() {
    return zeroOrMore(anyOf(" \t"));
  }

  public RootNode parse(StringBuilderVar body) {
    // The pegdown code doesn't provide enough visibility to directly
    // use its existing parsing rules. Recurse manually for inner text
    // parsing within a block.
    return parser.parseMarkdown(body.getChars());
  }
}

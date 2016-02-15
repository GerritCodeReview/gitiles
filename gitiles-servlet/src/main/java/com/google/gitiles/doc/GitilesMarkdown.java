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

import com.google.common.base.Throwables;
import com.google.gitiles.GitilesView;

import org.joda.time.Duration;
import org.parboiled.Rule;
import org.parboiled.common.Factory;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.support.StringBuilderVar;
import org.parboiled.support.Var;
import org.pegdown.Parser;
import org.pegdown.ParsingTimeoutException;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.Node;
import org.pegdown.ast.RootNode;
import org.pegdown.ast.SimpleNode;
import org.pegdown.plugins.BlockPluginParser;
import org.pegdown.plugins.InlinePluginParser;
import org.pegdown.plugins.PegDownPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Parses Gitiles extensions to markdown. */
public class GitilesMarkdown extends Parser
    implements BlockPluginParser, InlinePluginParser {
  private static final Logger log = LoggerFactory.getLogger(MarkdownUtil.class);

  // SUPPRESS_ALL_HTML is enabled to permit hosting arbitrary user content
  // while avoiding XSS style HTML, CSS and JavaScript injection attacks.
  //
  // HARDWRAPS is disabled to permit line wrapping within paragraphs to
  // make the source file easier to read in 80 column terminals without
  // this impacting the rendered formatting.
  private static final int MD_OPTIONS = (ALL | SUPPRESS_ALL_HTML) & ~(HARDWRAPS);

  public static RootNode parseFile(Duration parseTimeout, GitilesView view,
      String path, String md) {
    if (md == null) {
      return null;
    }

    try {
      try {
        return newParser(parseTimeout).parseMarkdown(md.toCharArray());
      } catch (ParserRuntimeException e) {
        Throwables.propagateIfInstanceOf(e.getCause(), ParsingTimeoutException.class);
        throw e;
      }
    } catch (ParsingTimeoutException e) {
      log.error("timeout {} ms rendering {}/{} at {}",
          parseTimeout.getMillis(),
          view.getRepositoryName(),
          path,
          view.getRevision().getName());
      return null;
    }
  }

  private static PegDownProcessor newParser(Duration parseDeadline) {
    PegDownPlugins plugins = new PegDownPlugins.Builder()
        .withPlugin(GitilesMarkdown.class, parseDeadline)
        .build();
    return new PegDownProcessor(MD_OPTIONS, parseDeadline.getMillis(), plugins);
  }

  private final Duration parseTimeout;
  private PegDownProcessor parser;

  GitilesMarkdown(Duration parseTimeout) {
    super(MD_OPTIONS, parseTimeout.getMillis(), DefaultParseRunnerProvider);
    this.parseTimeout = parseTimeout;
  }

  @Override
  public Rule[] blockPluginRules() {
    return new Rule[] {
        cols(),
        hr(),
        iframe(),
        note(),
        toc(),
    };
  }

  @Override
  public Rule[] inlinePluginRules() {
    return new Rule[]{
        namedAnchorHtmlStyle(),
        namedAnchorMarkdownExtensionStyle(),
    };
  }

  public Rule toc() {
    return NodeSequence(
        string("[TOC]"),
        push(new TocNode()));
  }

  public Rule hr() {
    // GitHub flavor markdown recognizes "--" as a rule.
    return NodeSequence(
        NonindentSpace(), string("--"), zeroOrMore('-'), Newline(),
        oneOrMore(BlankLine()),
        push(new SimpleNode(SimpleNode.Type.HRule)));
  }

  public Rule namedAnchorHtmlStyle() {
    StringBuilderVar name = new StringBuilderVar();
    return NodeSequence(
        Sp(), string("<a"),
        Spn1(),
        sequence(string("name="), attribute(name)),
        Spn1(), '>',
        Spn1(), string("</a>"),
        push(new NamedAnchorNode(name.getString())));
  }

  public Rule namedAnchorMarkdownExtensionStyle() {
    StringBuilderVar name = new StringBuilderVar();
    return NodeSequence(
        Sp(), string("{#"), anchorId(name), '}',
        push(new NamedAnchorNode(name.getString())));
  }

  public Rule anchorId(StringBuilderVar name) {
    return sequence(zeroOrMore(testNot('}'), ANY), name.append(match()));
  }

  public Rule iframe() {
    StringBuilderVar src = new StringBuilderVar();
    StringBuilderVar h = new StringBuilderVar();
    StringBuilderVar w = new StringBuilderVar();
    StringBuilderVar b = new StringBuilderVar();
    return NodeSequence(
        string("<iframe"),
        oneOrMore(
          sequence(
            Spn1(),
            firstOf(
              sequence(string("src="), attribute(src)),
              sequence(string("height="), attribute(h)),
              sequence(string("width="), attribute(w)),
              sequence(string("frameborder="), attribute(b))
            ))),
        Spn1(), '>',
        Spn1(), string("</iframe>"),
        push(new IframeNode(src.getString(),
            h.getString(), w.getString(),
            b.getString())));
  }

  public Rule attribute(StringBuilderVar var) {
    return firstOf(
      sequence('"', zeroOrMore(testNot('"'), ANY), var.append(match()), '"'),
      sequence('\'', zeroOrMore(testNot('\''), ANY), var.append(match()), '\''));
  }

  public Rule note() {
    StringBuilderVar body = new StringBuilderVar();
    return NodeSequence(
        string("***"), Sp(), typeOfNote(), Newline(),
        oneOrMore(
          testNot(string("***"), Newline()),
          Line(body)),
        string("***"), Newline(),
        push(new DivNode(popAsString(), parse(body))));
  }

  public Rule typeOfNote() {
    return firstOf(
        sequence(string("note"), push(match())),
        sequence(string("promo"), push(match())),
        sequence(string("aside"), push(match())));
  }

  @SuppressWarnings("unchecked")
  public Rule cols() {
    StringBuilderVar body = new StringBuilderVar();
    return NodeSequence(
        colsTag(), columnWidths(), Newline(),
        oneOrMore(
            testNot(colsTag(), Newline()),
            Line(body)),
        colsTag(), Newline(),
        push(new ColsNode((List<ColsNode.Column>) pop(), parse(body))));
  }

  public Rule colsTag() {
    return string("|||---|||");
  }

  public Rule columnWidths() {
    ListVar widths = new ListVar();
    return sequence(
      zeroOrMore(
        sequence(
          Sp(), optional(ch(',')), Sp(),
          columnWidth(widths))),
      push(widths.get()));
  }

  public Rule columnWidth(ListVar widths) {
    StringBuilderVar s = new StringBuilderVar();
    return sequence(
      optional(sequence(ch(':'), s.append(':'))),
      oneOrMore(digit()), s.append(match()),
      widths.get().add(parse(s.get().toString())));
  }

  static ColsNode.Column parse(String spec) {
    ColsNode.Column c = new ColsNode.Column();
    if (spec.startsWith(":")) {
      c.empty = true;
      spec = spec.substring(1);
    }
    c.span = Integer.parseInt(spec, 10);
    return c;
  }

  public List<Node> parse(StringBuilderVar body) {
    // The pegdown code doesn't provide enough visibility to directly
    // use its existing parsing rules. Recurse manually for inner text
    // parsing within a block.
    if (parser == null) {
      parser = newParser(parseTimeout);
    }
    return parser.parseMarkdown(body.getChars()).getChildren();
  }

  public static class ListVar extends Var<List<Object>> {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ListVar() {
      super(new Factory() {
        @Override
        public Object create() {
          return new ArrayList<>();
        }
      });
    }
  }
}

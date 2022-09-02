// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gitiles.doc;

import static com.google.gitiles.doc.SmartQuoted.Type.DOUBLE;
import static com.google.gitiles.doc.SmartQuoted.Type.SINGLE;

import com.google.gitiles.doc.SmartQuoted.Type;
import org.commonmark.Extension;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.parser.Parser.ParserExtension;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.parser.delimiter.DelimiterRun;

/** Uses smart quotes for ' and ". */
public class SmartQuotedExtension implements ParserExtension {
  public static Extension create() {
    return new SmartQuotedExtension();
  }

  private SmartQuotedExtension() {}

  @Override
  public void extend(Parser.Builder builder) {
    builder.customDelimiterProcessor(new QuotedProcessor(SINGLE, '\''));
    builder.customDelimiterProcessor(new QuotedProcessor(DOUBLE, '"'));
  }

  private static void quote(Type type, Text opener, Text closer) {
    SmartQuoted quote = new SmartQuoted();
    quote.setType(type);
    for (Node t = opener.getNext(); t != null && t != closer; ) {
      Node next = t.getNext();
      quote.appendChild(t);
      t = next;
    }
    opener.insertAfter(quote);
  }

  /** Parses single and double quoted strings for smart quotes. */
  private static class QuotedProcessor implements DelimiterProcessor {
    private final SmartQuoted.Type type;
    private final char delim;

    QuotedProcessor(SmartQuoted.Type type, char open) {
      this.type = type;
      this.delim = open;
    }

    @Override
    public char getOpeningCharacter() {
      return delim;
    }

    @Override
    public char getClosingCharacter() {
      return delim;
    }

    @Override
    public int getMinLength() {
      return 1;
    }

    @Override
    public int process(DelimiterRun openingRun, DelimiterRun closingRun) {
      quote(type, openingRun.getOpener(), closingRun.getCloser());
      return 1;
    }
  }
}

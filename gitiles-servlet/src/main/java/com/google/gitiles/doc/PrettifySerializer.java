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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.pegdown.Printer;
import org.pegdown.VerbatimSerializer;
import org.pegdown.ast.VerbatimNode;

import prettify.PrettifyParser;
import prettify.parser.Prettify;
import syntaxhighlight.ParseResult;

import java.util.List;
import java.util.Map;

/** Applies prettify to a verbatim block such as {@code ``` java}. */
class PrettifySerializer implements VerbatimSerializer {
  static final Map<String, VerbatimSerializer> MAP;

  static {
    PrettifySerializer i = new PrettifySerializer();
    ImmutableMap.Builder<String, VerbatimSerializer> m = ImmutableMap.builder();
    for (String name : new String[] {"c", "cpp", "java", "csharp", "coffee",
        "js", "javascript", "perl", "python", "ruby", "sh", "css", "clj",
        "dart", "erlang", "go", "lisp", "llvm", "lua", "matlab", "r", "scala",
        "sql", "tex", "tcl", "yaml"}) {
      m.put(name, i);
    }
    MAP = m.build();
  }

  @Override
  public void serialize(VerbatimNode node, Printer printer) {
    printer.println().print("<pre><code>");
    String lang = node.getType();
    String text = node.getText();

    // print HTML breaks for all initial newlines
    while (text.charAt(0) == '\n') {
        printer.print("<br/>");
        text = text.substring(1);
    }

    List<ParseResult> parsed = parse(lang, text);
    if (parsed != null) {
      int last = 0;
      for (ParseResult r : parsed) {
        write(printer, null, text, last, r.getOffset());
        last = r.getOffset() + r.getLength();
        write(printer, r.getStyleKeysString(), text, r.getOffset(), last);
      }
      if (last < text.length()) {
        write(printer, null, text, last, text.length());
      }
    } else {
      printer.printEncoded(text);
    }
    printer.print("</code></pre>");
  }

  private static void write(Printer printer, String classes,
      String s, int start, int end) {
    if (end - start > 0) {
      if (Strings.isNullOrEmpty(classes)) {
        classes = Prettify.PR_PLAIN;
      }
      printer.print("<span class=\"").print(classes).print("\">");
      printer.printEncoded(s.substring(start, end));
      printer.print("</span>");
    }
  }

  private List<ParseResult> parse(String lang, String text) {
    try {
      return new PrettifyParser().parse(lang, text);
    } catch (StackOverflowError e) {
      return null;
    }
  }
}

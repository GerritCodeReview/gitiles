// Copyright 2023 Google Inc. All Rights Reserved.
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

import com.google.gitiles.MetaInfResources;
import org.commonmark.Extension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.parser.PostProcessor;

public class MermaidExtension implements Parser.ParserExtension {

  private MermaidExtension() {}

  public static Extension create() {
    return new MermaidExtension();
  }

  @Override
  public void extend(Parser.Builder parserBuilder) {
    parserBuilder.postProcessor(new MermaidPostProcessor());
  }

  private static class MermaidPostProcessor implements PostProcessor {
    @Override
    public Node process(Node node) {
      node.accept(new MermaidVisitor());
      return node;
    }
  }

  private static class MermaidVisitor extends AbstractVisitor {

    private boolean addScript;

    @Override
    public void visit(Document document) {
      super.visit(document);
      if (!addScript) {
        return;
      }
      HtmlSafeScript script = new HtmlSafeScript();
      script.type = "module";
      script.literal =
          String.format(
              "import mermaid from '%s'",
              MetaInfResources.createUrlPath("webjars/mermaid/10.2.4/dist/mermaid.esm.min.mjs"));
      document.appendChild(script);
    }

    @Override
    public void visit(FencedCodeBlock fencedCodeBlock) {
      if (!"mermaid".equals(fencedCodeBlock.getInfo())) {
        super.visit(fencedCodeBlock);
        return;
      }

      addScript = true;

      HtmlPreBlock pre = new HtmlPreBlock();
      pre.className = "mermaid";
      pre.literal = fencedCodeBlock.getLiteral();
      fencedCodeBlock.insertAfter(pre);
      fencedCodeBlock.unlink();
    }
  }
}

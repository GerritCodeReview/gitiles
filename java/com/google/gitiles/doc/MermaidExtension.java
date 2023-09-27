package com.google.gitiles.doc;

import org.commonmark.Extension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.parser.PostProcessor;

/**
 * @author Réda Housni Alaoui
 */
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
      script.literal = "import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10.4.0/+esm'";
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

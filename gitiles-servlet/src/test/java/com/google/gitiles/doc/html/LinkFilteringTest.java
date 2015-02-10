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

package com.google.gitiles.doc.html;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.pegdown.LinkRenderer.Rendering;
import org.pegdown.ast.AutoLinkNode;
import org.pegdown.ast.ExpImageNode;
import org.pegdown.ast.ExpLinkNode;
import org.pegdown.ast.TextNode;
import org.pegdown.ast.WikiLinkNode;

@RunWith(JUnit4.class)
public class LinkFilteringTest {
  private GitLinkRenderer links;

  @Before
  public void setUp() {
    links = new GitLinkRenderer(null);
  }

  @Test
  public void autoLinks() {
    Rendering r = links.render(new AutoLinkNode("http://example.com"));
    assertEquals("http://example.com", r.href);
    assertEquals("http://example.com", r.text);

    r = links.render(new AutoLinkNode("http://example.com/<"));
    assertEquals("http://example.com/%3C", r.href);
    assertEquals("http://example.com/&lt;", r.text);

    r = links.render(new AutoLinkNode("javascript:if(1<2)alert(1)"));
    assertEquals("#zSoyz", r.href);
    assertEquals("javascript:if(1&lt;2)alert(1)", r.text);
  }

  @Test
  public void wikiLinks() {
    Rendering r = links.render(new WikiLinkNode("foo bar"));
    assertEquals("foo-bar.md", r.href);
    assertEquals("foo bar", r.text);

    r = links.render(new WikiLinkNode("foo<b>bar"));
    assertEquals("foo%3Cb%3Ebar.md", r.href);
    assertEquals("foo&lt;b&gt;bar", r.text);

    r = links.render(new WikiLinkNode("javascript:alert(1)"));
    assertEquals("#zSoyz", r.href);
  }

  @Test
  public void linkRenderingFiltersURLs() {
    // From javatests/com/google/template/soy/shared/restricted/SanitizersTest.java

    // Test filtering of URI starts.
    assertEquals("#zSoyz", link("ftp:"));
    assertEquals("#zSoyz", link("irc:"));
    assertEquals("#zSoyz", link("gopher:"));
    assertEquals("#zSoyz", link("file:///etc/passwd"));
    assertEquals("#zSoyz", link("javascript:"));
    assertEquals("#zSoyz", link("javascript:alert(1337)"));
    assertEquals("#zSoyz", link("vbscript:alert(1337)"));
    assertEquals("#zSoyz", link("livescript:alert(1337)"));
    assertEquals("#zSoyz", link("data:,alert(1337)"));
    assertEquals("#zSoyz", link("data:text/javascript,alert%281337%29"));
    assertEquals("#zSoyz", link("javascript:handleClick()"));
    assertFalse(link("javascript\uff1aalert(1337);").contains("javascript\uff1a"));

    // Testcases from http://ha.ckers.org/xss.html
    assertEquals("#zSoyz", link("JaVaScRiPt:alert(1337)"));
    assertEquals(
        "#zSoyz",
        link(
            // Using HTML entities to obfuscate javascript:alert('XSS');
            "&#106;&#97;&#118;&#97;&#115;&#99;&#114;&#105;&#112;&#116;" +
            "&#58;&#97;&#108;&#101;&#114;&#116;&#40;&#39;&#88;&#83;&#83;&#39;&#41;"));
    assertEquals(
        "#zSoyz",
        link(  // Using longer HTML entities to obfuscate the same.
            "&#0000106&#0000097&#0000118&#0000097&#0000115&#0000099&#0000114&#0000105" +
            "&#0000112&#0000116&#0000058&#0000097&#0000108&#0000101&#0000114&#0000116" +
            "&#0000040&#0000039&#0000088&#0000083&#0000083&#0000039&#0000041"));
    assertEquals(
        "#zSoyz",
        link(  // Using hex HTML entities to obfuscate the same.
            "&#x6A&#x61&#x76&#x61&#x73&#x63&#x72&#x69&#x70&#x74" +
            "&#x3A&#x61&#x6C&#x65&#x72&#x74&#x28&#x27&#x58&#x53&#x53&#x27&#x29"));
    assertEquals("#zSoyz", link("jav\tascript:alert('XSS');"));
    assertEquals("#zSoyz", link("jav&#x09;ascript:alert('XSS');"));
    assertEquals("#zSoyz", link("jav&#x0A;ascript:alert('XSS');"));
    assertEquals("#zSoyz", link("jav&#x0D;ascript:alert('XSS');"));
    assertEquals(
        "#zSoyz",
        link(
            "\nj\n\na\nv\na\ns\nc\nr\ni\np\nt\n:\na\nl\ne\nr\nt\n(\n1\n3\n3\n7\n)"));
    assertEquals("#zSoyz", link("\u000e  javascript:alert('XSS');"));

    // Tests of filtering heirarchy within uri path (/.. etc )
    assertEquals("#zSoyz", link("a/../"));
    assertEquals("#zSoyz", link("/..?"));
    assertEquals("#zSoyz", link("http://bad.url.com../../s../.#.."));
    assertEquals("#zSoyz", link("http://badurl.com/normal/../unsafe"));

    // Things we should accept.
    assertEquals("http://google.com/", link("http://google.com/"));
    assertEquals("https://google.com/", link("https://google.com/"));
    assertEquals("HTTP://google.com/", link("HTTP://google.com/"));
    assertEquals("?a=b&c=d", link("?a=b&c=d"));
    assertEquals("?a=b:c&d=e", link("?a=b:c&d=e"));
    assertEquals("//foo.com:80/", link("//foo.com:80/"));
    assertEquals("//foo.com/", link("//foo.com/"));
    assertEquals("/foo:bar/", link("/foo:bar/"));
    assertEquals("#a:b", link("#a:b"));
    assertEquals("#", link("#"));
    assertEquals("/", link("/"));
    assertEquals("", link(""));
    assertEquals("../", link("../"));
    assertEquals(".%2E", link(".%2E"));
    assertEquals("..", link(".."));
    assertEquals("%2E%2E", link("%2E%2E"));
    assertEquals("%2e%2e", link("%2e%2e"));
    assertEquals("%2e.", link("%2e."));
    assertEquals("http://goodurl.com/.stuff/?/../.",
        link("http://goodurl.com/.stuff/?/../."));
    assertEquals("http://good.url.com../..s../.#..",
        link("http://good.url.com../..s../.#.."));
    assertEquals("http://goodurl.com/normal/%2e/unsafe?",
        link("http://goodurl.com/normal/%2e/unsafe?"));
  }

  private String link(String url) {
    String text = "click";
    Rendering r = links.render(new ExpLinkNode(null, url, new TextNode(text)), text);
    assertNotNull("render produces result", r);
    assertEquals(text, r.text);
    return r.href;
  }

  @Test
  public void imageRenderingFiltersDataURLs() {
    // From javatests/com/google/template/soy/shared/restricted/SanitizersTest.java

    String allBase64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz/+";
    String[] validImageDataUris = new String[] {
      "https://www.google.com",
      "https://www.google.com/foo.png",
      "data:image/png;base64," + allBase64Chars,
      "data:image/png;base64," + allBase64Chars + allBase64Chars,
      "data:image/png;base64," + allBase64Chars + "==",
      "data:image/gif;base64," + allBase64Chars,
      "data:image/tiff;base64," + allBase64Chars,
      "data:image/webp;base64," + allBase64Chars,
      "data:image/bmp;base64," + allBase64Chars
    };
    String[] invalidImageDataUris = new String[] {
      // Wrong protocol type (beta)
      "beta:image/foo;base64," + allBase64Chars,
      // Wrong MIME type (image/foo)
      "data:image/foo;base64," + allBase64Chars,
      // bake64 instead of base64
      "data:image/png;bake64," + allBase64Chars,
      // Invalid chars .()
      "data:image/png;base64,ABCD.()",
      // Relative URL's.
      "/foo",
      // Extra junk at beginning and end. To ensure regexp is multiline-safe.
      "\ndata:image/png;base64," + allBase64Chars,
      "xdata:image/png;base64," + allBase64Chars,
      ".data:image/png;base64," + allBase64Chars,
      "data:image/png;base64," + allBase64Chars + "\n",
      "data:image/png;base64," + allBase64Chars + "=x",
      "data:image/png;base64," + allBase64Chars + ".",
      // "=" in wrong place:
      "data:image/png;base64," + allBase64Chars + "=" + allBase64Chars,
      // MIME types that are fundamentally insecure.
      "data:image/svg+xml;base64," + allBase64Chars
    };

    for (String url : validImageDataUris) {
      String text = "alt";
      Rendering r = links.render(new ExpImageNode(null, url, new TextNode(text)), text);
      assertEquals(text, r.text);
      assertEquals(url, r.href);
    }

    for (String url : invalidImageDataUris) {
      String text = "alt";
      Rendering r = links.render(new ExpImageNode(null, url, new TextNode(text)), text);
      assertEquals(text, r.text);
      assertEquals(
          "expected fail for "+url,
          "data:image/gif;base64,zSoyz", r.href);
    }
  }
}

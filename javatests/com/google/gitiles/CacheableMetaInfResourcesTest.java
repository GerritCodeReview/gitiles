package com.google.gitiles;

import static com.google.common.truth.Truth.assertThat;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.net.HttpHeaders;
import java.util.regex.Matcher;
import org.junit.Test;

public class CacheableMetaInfResourcesTest extends ServletTest {

  @Test
  public void pathRegex() {
    assertThat("").doesNotMatch(CacheableMetaInfResources.PATH_REGEX);
    assertThat("/foo").doesNotMatch(CacheableMetaInfResources.PATH_REGEX);
    assertThat("/+cmir").doesNotMatch(CacheableMetaInfResources.PATH_REGEX);

    Matcher m = CacheableMetaInfResources.PATH_REGEX.matcher("/+cmir/foo/bar.js");
    assertThat(m.matches()).isTrue();
    assertThat(m.group(0)).isEqualTo("/+cmir/foo/bar.js");
    assertThat(m.group(1)).isEqualTo("/foo/bar.js");
  }

  @Test
  public void get() throws Exception {
    FakeHttpServletResponse res =
        buildResponse("/+cmir/foo/bar.txt", null, SC_OK, "http://notlocalhost");
    assertThat(res.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/plain");
    assertThat(res.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=31536000");
    assertThat(res.getActualBodyString()).isEqualTo("bar\n");
  }
}

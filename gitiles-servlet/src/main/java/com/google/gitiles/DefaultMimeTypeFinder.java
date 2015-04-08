package com.google.gitiles;

import java.net.URLConnection;

public class DefaultMimeTypeFinder implements MimeTypeFinder {
  @Override
  public String guessFromFileExtension(String filename) {
    return URLConnection.guessContentTypeFromName(filename);
  }
}

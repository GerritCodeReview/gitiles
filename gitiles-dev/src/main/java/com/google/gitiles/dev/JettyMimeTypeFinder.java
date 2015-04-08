package com.google.gitiles.dev;

import com.google.gitiles.MimeTypeFinder;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;

public class JettyMimeTypeFinder implements MimeTypeFinder {
  private MimeTypes mimeTypes = new MimeTypes();

  @Override
  public String guessFromFileExtension(String filename) {
    Buffer buffer = mimeTypes.getMimeByExtension(filename);
    if (buffer == null) {
      return null;
    }

    return buffer.toString();
  }
}

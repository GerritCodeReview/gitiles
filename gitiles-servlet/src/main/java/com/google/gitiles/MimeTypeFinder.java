package com.google.gitiles;

public interface MimeTypeFinder {
  public String guessFromFileExtension(String filename);
}

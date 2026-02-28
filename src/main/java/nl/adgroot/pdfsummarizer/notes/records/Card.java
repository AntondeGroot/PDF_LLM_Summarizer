package nl.adgroot.pdfsummarizer.notes.records;

import org.jetbrains.annotations.NotNull;

public record Card(String title, String markdown) {

  @NotNull
  @Override
  public String toString() {
    return markdown;
  }
}
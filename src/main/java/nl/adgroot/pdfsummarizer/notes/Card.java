package nl.adgroot.pdfsummarizer.notes;

public class Card {
  public final String title;
  public final String markdown;

  public Card(String title, String markdown) {
    this.title = title;
    this.markdown = markdown;
  }

  @Override
  public String toString() {
    return markdown;
  }
}
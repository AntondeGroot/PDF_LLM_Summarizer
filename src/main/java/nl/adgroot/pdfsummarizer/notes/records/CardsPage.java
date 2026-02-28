package nl.adgroot.pdfsummarizer.notes.records;

import static nl.adgroot.pdfsummarizer.notes.NotesWriter.safeFileName;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public record CardsPage(
    List<String> content,
    String topic,
    String chapter
) {

  private static final String SEPARATOR =
      "\n\n--------------------------------------------------\n\n";

  // Canonical constructor (ensures mutable list + non-null strings)
  public CardsPage {
    if (content == null) {
      content = new ArrayList<>();
    }
    topic = topic == null ? "" : safeFileName(topic);
    chapter = chapter == null ? "" : safeFileName(chapter);
  }

  // Constructor with topic + chapter
  public CardsPage(String topic, String chapter) {
    this(new ArrayList<>(), topic, chapter);
  }

  public void addCard(String card) {
   if (card == null || card.isBlank()) {
    return;
   }
    content.add(card);
  }

  public boolean hasContent() {
    return !content.isEmpty();
  }

  private String getHashtag() {
    return "#flashcards/" + topic + "\n" +
        "#flashcards/" + topic + "/" + chapter;
  }

  @NotNull
  @Override
  public String toString() {
    if (content.isEmpty()) {
      return "\n\n" + getHashtag();
    }

    String joined = String.join(SEPARATOR, content);
    return joined + "\n\n" + getHashtag();
  }
}

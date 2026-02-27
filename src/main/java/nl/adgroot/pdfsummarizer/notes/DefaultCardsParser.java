package nl.adgroot.pdfsummarizer.notes;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultCardsParser implements CardsParser {

  // splits on a line containing exactly ---
  private static final Pattern SPLIT = Pattern.compile("(?m)^---\\s*$");

  // tries to find YAML frontmatter title: ...
  private static final Pattern TITLE = Pattern.compile("(?m)^title:\\s*(.+)\\s*$");

  @Override
  public List<Card> parse(String markdown) {
    if (markdown == null || markdown.isBlank()) return List.of();

    String[] parts = SPLIT.split(markdown);
    List<Card> cards = new ArrayList<>();

    for (String part : parts) {
      String cardMd = part.trim();
      if (cardMd.isEmpty()) continue;

      String title = extractTitle(cardMd).orElse("Untitled Card");
      cards.add(new Card(title, cardMd + "\n"));
    }

    return cards;
  }

  private Optional<String> extractTitle(String md) {
    Matcher m = TITLE.matcher(md);
    if (!m.find()) return Optional.empty();

    String t = m.group(1).trim();
    t = t.replaceAll("^\"|\"$", "");
    return t.isBlank() ? Optional.empty() : Optional.of(t);
  }
}
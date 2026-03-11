package nl.adgroot.pdfsummarizer.notes;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nl.adgroot.pdfsummarizer.AppLogger;
import nl.adgroot.pdfsummarizer.notes.records.Card;

public class DefaultCardsParser implements CardsParser {

  private static final AppLogger log = AppLogger.getLogger(DefaultCardsParser.class);

  // splits on a line containing exactly ---
  private static final Pattern SPLIT = Pattern.compile("(?m)^---\\s*$");

  // tries to find YAML frontmatter title: ...
  private static final Pattern TITLE = Pattern.compile("(?m)^title:\\s*(.+)\\s*$");

  // a line containing only ?
  private static final Pattern SEPARATOR = Pattern.compile("(?m)^\\?\\s*$");

  // a line ending with ? (the malformed case: question mark glued to the question line)
  private static final Pattern QUESTION_LINE = Pattern.compile("(?m)^(.+\\?)[\\t ]*$");

  @Override
  public List<Card> parse(String markdown) {
    if (markdown == null || markdown.isBlank()) return List.of();

    String[] parts = SPLIT.split(markdown);
    List<Card> cards = new ArrayList<>();

    for (String part : parts) {
      String cardMd = part.trim();
      if (cardMd.isEmpty()) continue;

      cardMd = ensureSeparator(cardMd);

      String title = extractTitle(cardMd).orElse("Untitled Card");
      cards.add(new Card(title, cardMd + "\n"));
    }

    return cards;
  }

  /**
   * Returns the card markdown with a guaranteed standalone {@code ?} separator,
   * salvaging it when possible.
   */
  private String ensureSeparator(String cardMd) {
    if (SEPARATOR.matcher(cardMd).find()) {
      return cardMd; // already well-formed
    }

    // Try to salvage: find the first line that ends with ?
    Matcher m = QUESTION_LINE.matcher(cardMd);
    if (m.find()) {
      int end = m.end();
      String salvaged = cardMd.substring(0, end) + "\n?\n" + cardMd.substring(end).stripLeading();
      log.warn("Malformed card salvaged — inserted missing '?' separator line. Check prompt strictness.\n%s", cardMd);
      return salvaged;
    }

    log.error("Malformed card detected — no question mark found, cannot form a Q&A:\n%s", cardMd);
    return cardMd;
  }

  private Optional<String> extractTitle(String md) {
    Matcher m = TITLE.matcher(md);
    if (!m.find()) return Optional.empty();

    String t = m.group(1).trim();
    t = t.replaceAll("^\"|\"$", "");
    return t.isBlank() ? Optional.empty() : Optional.of(t);
  }
}
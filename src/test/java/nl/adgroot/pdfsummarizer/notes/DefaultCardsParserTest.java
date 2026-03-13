package nl.adgroot.pdfsummarizer.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import nl.adgroot.pdfsummarizer.notes.records.Card;
import org.junit.jupiter.api.Test;

class DefaultCardsParserTest {

  private final DefaultCardsParser parser = new DefaultCardsParser();

  // ── Empty / blank input ──────────────────────────────────────────────────

  @Test
  void parse_null_returnsEmptyList() {
    assertTrue(parser.parse(null).isEmpty());
  }

  @Test
  void parse_blankString_returnsEmptyList() {
    assertTrue(parser.parse("   \n  ").isEmpty());
  }

  // ── Well-formed cards ────────────────────────────────────────────────────

  @Test
  void parse_singleWellFormedCard_returnsOneCard() {
    String md = """
        What is Java?
        ?
        A general-purpose programming language.
        """;

    List<Card> cards = parser.parse(md);
    assertEquals(1, cards.size());
  }

  @Test
  void parse_multipleWellFormedCards_returnsAllCards() {
    String md = """
        Question one?
        ?
        Answer one.
        ---
        Question two?
        ?
        Answer two.
        ---
        Question three?
        ?
        Answer three.
        """;

    List<Card> cards = parser.parse(md);
    assertEquals(3, cards.size());
  }

  @Test
  void parse_cardMarkdownEndsWithNewline() {
    String md = "What is Java?\n?\nA programming language.";

    List<Card> cards = parser.parse(md);
    assertTrue(cards.getFirst().markdown().endsWith("\n"));
  }

  // ── Title extraction ─────────────────────────────────────────────────────

  @Test
  void parse_titleLine_extractsTitle() {
    String md = """
        title: What is Java?
        What is Java?
        ?
        A programming language.
        """;

    List<Card> cards = parser.parse(md);
    assertEquals("What is Java?", cards.getFirst().title());
  }

  @Test
  void parse_titleLineWithQuotes_stripsQuotes() {
    String md = """
        title: "Quoted title"
        Quoted title?
        ?
        Answer.
        """;

    List<Card> cards = parser.parse(md);
    assertEquals("Quoted title", cards.getFirst().title());
  }

  @Test
  void parse_noTitleLine_usesUntitledCard() {
    String md = """
        What is Java?
        ?
        A programming language.
        """;

    List<Card> cards = parser.parse(md);
    assertEquals("Untitled Card", cards.getFirst().title());
  }

  // ── Salvage: question mark glued to the question line ────────────────────

  @Test
  void parse_missingStandaloneSeparator_questionMarkGluedToQuestion_isSalvaged() {
    // LLM put ? at the end of the question line instead of on its own line
    String md = """
        What is Java?
        A programming language.
        """;

    List<Card> cards = parser.parse(md);
    assertEquals(1, cards.size());
    assertTrue(cards.getFirst().markdown().contains("\n?\n"),
        "Expected salvaged card to contain standalone '?' separator");
  }

  @Test
  void parse_multipleCardsOneMalformed_salvagesMalformedKeepsWellFormed() {
    String md = """
        Well formed question?
        ?
        Answer.
        ---
        Malformed question?
        Answer without separator.
        """;

    List<Card> cards = parser.parse(md);
    assertEquals(2, cards.size());
    assertTrue(cards.get(1).markdown().contains("\n?\n"),
        "Expected malformed card to be salvaged with standalone '?' separator");
  }

  // ── Separator edge cases ─────────────────────────────────────────────────

  @Test
  void parse_separatorWithTrailingWhitespace_isRecognised() {
    String md = "Question?\n?   \nAnswer.";

    List<Card> cards = parser.parse(md);
    assertEquals(1, cards.size());
  }

  @Test
  void parse_emptyPartsBetweenSeparators_areSkipped() {
    String md = """
        Question?
        ?
        Answer.
        ---
        ---
        """;

    // second --- produces an empty part that must be skipped
    List<Card> cards = parser.parse(md);
    assertEquals(1, cards.size());
  }
}

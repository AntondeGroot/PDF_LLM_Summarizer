package nl.adgroot.pdfsummarizer.pdf.parsing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class PdfObjectTest {

  private static PdfObject newObj(int index) {
    return new PdfObject(index, "Chapter 1", null, "page text");
  }

  @Test
  void getOriginalPageNr_defaultsToIndexPlusOne() {
    PdfObject obj = newObj(4);
    assertEquals(5, obj.getOriginalPageNr());
  }

  @Test
  void getOriginalPageNr_usesExplicitValue() {
    PdfObject obj = new PdfObject(0, 42, "Chapter 1", null, "text");
    assertEquals(42, obj.getOriginalPageNr());
  }

  @Test
  void getCards_emptyByDefault() {
    PdfObject obj = newObj(0);
    assertNotNull(obj.getCards());
    assertTrue(obj.getCards().isEmpty());
  }

  @Test
  void setCards_storesCards() {
    PdfObject obj = newObj(0);
    obj.setCards(List.of("Card A", "Card B"));
    assertEquals(List.of("Card A", "Card B"), obj.getCards());
  }

  @Test
  void setCards_defensiveCopy_mutatingOriginalDoesNotAffectStored() {
    PdfObject obj = newObj(0);
    java.util.List<String> original = new java.util.ArrayList<>(List.of("X"));
    obj.setCards(original);
    original.add("Y");
    assertEquals(1, obj.getCards().size(), "Stored cards should not be affected by external mutation");
  }

  @Test
  void setCards_null_storesEmptyList() {
    PdfObject obj = newObj(0);
    obj.setCards(List.of("something"));
    obj.setCards(null);
    assertNotNull(obj.getCards());
    assertTrue(obj.getCards().isEmpty());
  }

  @Test
  void setCards_doesNotAffectNotes() {
    PdfObject obj = newObj(0);
    obj.setNotes("some notes");
    obj.setCards(List.of("Card A"));
    assertEquals("some notes", obj.getNotes());
  }

  @Test
  void setNotes_doesNotAffectCards() {
    PdfObject obj = newObj(0);
    obj.setCards(List.of("Card A"));
    obj.setNotes("some notes");
    assertEquals(List.of("Card A"), obj.getCards());
  }
}
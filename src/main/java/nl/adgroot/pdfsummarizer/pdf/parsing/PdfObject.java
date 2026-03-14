package nl.adgroot.pdfsummarizer.pdf.parsing;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;

public class PdfObject {

  private final int index;           // 0..n-1 content index (stable identity)
  private final int originalPageNr;  // 1-based page number in the original PDF
  private final String chapter;      // chapter header/title
  private final PDDocument document; // single-page PDDocument
  private final String textReadFromPdf;         // page text

  private List<String> cards = new ArrayList<>(); // LLM-generated cards for this page
  private String notes;              // formatted notes (per page, for preview PDF)
  private String summary;            // optional future

  /** Intermediate outputs from the three-stage pipeline, stored for preview display. */
  public record StageDebugInfo(String concepts, String rawCards) {}
  private StageDebugInfo stageDebugInfo;

  public PdfObject(int index, String chapter, PDDocument document, String text) {
    this(index, index + 1, chapter, document, text);
  }

  public PdfObject(int index, int originalPageNr, String chapter, PDDocument document, String text) {
    this.index = index;
    this.originalPageNr = originalPageNr;
    this.chapter = chapter;
    this.document = document;
    this.textReadFromPdf = text;
  }

  public int getIndex() {
    return index;
  }

  public int getOriginalPageNr() {
    return originalPageNr;
  }

  public String getChapter() {
    return chapter;
  }

  public PDDocument getDocument() {
    return document;
  }

  public String getTextReadFromPdf() {
    return textReadFromPdf;
  }

  public List<String> getCards() {
    return cards;
  }

  public void setCards(List<String> cards) {
    this.cards = cards != null ? new ArrayList<>(cards) : new ArrayList<>();
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public boolean hasNotes() {
    return notes != null && !notes.isBlank();
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public StageDebugInfo getStageDebugInfo() {
    return stageDebugInfo;
  }

  public void setStageDebugInfo(StageDebugInfo stageDebugInfo) {
    this.stageDebugInfo = stageDebugInfo;
  }
}
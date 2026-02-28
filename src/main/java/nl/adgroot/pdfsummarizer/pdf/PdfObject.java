package nl.adgroot.pdfsummarizer.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;

public class PdfObject {

  private final int index;           // 0..n-1 after preview selection
  private final String chapter;      // chapter header/title
  private final PDDocument document; // single-page PDDocument
  private final String text;         // page text

  private String notes;              // generated notes (per page)
  private String summary;            // optional future

  public PdfObject(int index, String chapter, PDDocument document, String text) {
    this.index = index;
    this.chapter = chapter;
    this.document = document;
    this.text = text;
  }

  public int getIndex() {
    return index;
  }

  public String getChapter() {
    return chapter;
  }

  public PDDocument getDocument() {
    return document;
  }

  public String getText() {
    return text;
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
}
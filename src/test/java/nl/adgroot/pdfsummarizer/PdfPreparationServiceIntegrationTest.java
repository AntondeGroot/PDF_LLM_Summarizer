package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.pdf.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.PdfPreparationService;
import nl.adgroot.pdfsummarizer.pdf.PreparedPdf;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration-style tests that use real PDFBox splitter + extractor + PdfPreparationService.
 *
 * PDFBox 3.x compatible (uses Standard14Fonts).
 */
class PdfPreparationServiceIntegrationTest {

  @Test
  void loadAndPrepare_previewNonRandom_selectsFirstN_andSelectedPdfObjectsMatchThoseIndexes() throws Exception {
    int contentPages = 6;
    Path pdf = buildTestPdf(contentPages);

    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 2;

    PdfPreparationService svc = new PdfPreparationService(new PdfBoxTextExtractor(), new PdfBoxPdfSplitter());
    PreparedPdf prepared = svc.loadAndPrepare(pdf, cfg);

    List<PdfObject> selected = prepared.pdfPages();

    assertEquals(2, selected.size(), "Expected 2 selected PdfObjects");
    assertEquals(2, prepared.parsed().getContent().size(), "Expected parsed content trimmed to 2 pages");

    // Strongest assertion: the PdfObject text should match the first content markers.
    assertTrue(selected.get(0).getText().contains("CONTENT-0"),
        "First selected PdfObject should contain marker CONTENT-0 in its text");
    assertTrue(selected.get(1).getText().contains("CONTENT-1"),
        "Second selected PdfObject should contain marker CONTENT-1 in its text");

    // Optional: also validate actual PDF rendering/text extraction from split PDDocument pages.
    assertTrue(extractSinglePageText(selected.get(0).getDocument()).contains("CONTENT-0"),
        "First selected PDF page should contain marker CONTENT-0");
    assertTrue(extractSinglePageText(selected.get(1).getDocument()).contains("CONTENT-1"),
        "Second selected PDF page should contain marker CONTENT-1");
  }

  @Disabled("fix this for random preview")
  @Test
  void loadAndPrepare_previewRandom_selectsN_pages_andPdfObjectsAndDocumentsStayAligned() throws Exception {
    int contentPages = 10;
    Path pdf = buildTestPdf(contentPages);

    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = true;
    cfg.preview.nrPages = 5;

    PdfPreparationService svc = new PdfPreparationService(new PdfBoxTextExtractor(), new PdfBoxPdfSplitter());
    PreparedPdf prepared = svc.loadAndPrepare(pdf, cfg);

    List<PdfObject> selected = prepared.pdfPages();

    assertEquals(5, selected.size(), "Expected 5 selected PdfObjects");
    assertEquals(5, prepared.parsed().getContent().size(), "Expected parsed content trimmed to 5 pages");

    // Alignment check: for each selected PdfObject, the extracted PDF text should include
    // the marker that is also in PdfObject.text (same page).
    for (PdfObject obj : selected) {
      String objText = obj.getText();
      String pdfText = extractSinglePageText(obj.getDocument());

      // Find the first CONTENT-k marker in objText and assert it is also in the PDF page.
      // This makes the test robust even if Page#toString includes extra metadata.
      String marker = firstContentMarker(objText);
      assertTrue(marker != null && !marker.isBlank(), "Expected a CONTENT-k marker in PdfObject text");
      assertTrue(pdfText.contains(marker),
          "Expected selected PDF page text to contain marker " + marker + " but got:\n" + pdfText);
    }
  }

  // ---------------- helpers ----------------

  private static AppConfig minimalConfig() {
    AppConfig cfg = new AppConfig();
    cfg.cards.nrOfLinesUsedForContext = 0;
    cfg.preview.enabled = false;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 5;
    return cfg;
  }

  /**
   * Builds a PDF whose structure satisfies your TOC heuristics AND whose content pages
   * include stable markers "CONTENT-i" that we can assert after slicing/preview selection.
   *
   * Layout:
   *  p0: "Table of Contents"
   *  p1: TOC-like lines ending with numbers
   *  p2: non-TOC
   *  p3..: content pages. First content page contains "Chapter 1: Intro" so PDFUtil.getStringPagesWithoutTOC finds it.
   */
  private static Path buildTestPdf(int contentPages) throws Exception {
    Path pdf = Files.createTempFile("prep-it-", ".pdf");

    try (PDDocument doc = new PDDocument()) {

      addPageWithText(doc, "Table of Contents");

      addPageWithText(doc,
          "Chapter 1: Intro 1\n" +
              "Chapter 2: Basics 5\n" +
              "Chapter 3: Advanced 9\n");

      addPageWithText(doc, "Preface\nThis is not a TOC page.\n");

      // first content page must contain chapter header so getStringPagesWithoutTOC can find it
      addPageWithText(doc, "Chapter 1: Intro\nCONTENT-0\n");

      for (int i = 1; i < contentPages; i++) {
        addPageWithText(doc, "CONTENT-" + i + "\n");
      }

      doc.save(pdf.toFile());
    }

    return pdf;
  }

  private static void addPageWithText(PDDocument doc, String text) throws Exception {
    PDPage page = new PDPage();
    doc.addPage(page);

    try (PDPageContentStream cs = new PDPageContentStream(
        doc,
        page,
        PDPageContentStream.AppendMode.OVERWRITE,
        true,
        true
    )) {
      cs.beginText();
      cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      cs.newLineAtOffset(50, 750);

      // Write line by line so \n shows up in extracted text
      for (String line : text.split("\n", -1)) {
        if (!line.isEmpty()) {
          cs.showText(line);
        }
        cs.newLineAtOffset(0, -14);
      }

      cs.endText();
    }
  }

  private static String extractSinglePageText(PDDocument singlePageDoc) throws Exception {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(1);
    stripper.setEndPage(1);
    return stripper.getText(singlePageDoc);
  }

  private static String firstContentMarker(String text) {
    if (text == null) return null;
    int idx = text.indexOf("CONTENT-");
    if (idx < 0) return null;

    int end = idx;
    while (end < text.length()) {
      char c = text.charAt(end);
      if (c == '\n' || c == '\r' || c == ' ' || c == '\t') break;
      end++;
    }
    return text.substring(idx, end);
  }
}
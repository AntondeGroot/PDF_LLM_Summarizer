package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.pdf.PdfPreparationService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PdfPreparationServiceTest {

  private final List<PDDocument> docsToClose = new ArrayList<>();

  @AfterEach
  void cleanup() {
    for (PDDocument d : docsToClose) {
      try { d.close(); } catch (Exception ignored) {}
    }
    docsToClose.clear();
  }

  @Test
  void applyPreviewIfEnabled_previewDisabled_returnsSamePages_andDoesNotTrimContent() throws Exception {
    PdfPreparationService svc = new PdfPreparationService(new PdfBoxTextExtractor(), new PdfBoxPdfSplitter());

    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = false;

    ParsedPDF parsedPdf = new ParsedPDF(fakePagesWithTocAndContent(), 0);
    int originalSize = parsedPdf.getContent().size();

    List<PDDocument> pdfPages = newPdfPages(originalSize);

    List<PDDocument> result = invokeApplyPreviewIfEnabled(svc, cfg, parsedPdf, pdfPages);

    assertSame(pdfPages, result, "preview disabled should return same list instance");
    assertEquals(originalSize, parsedPdf.getContent().size(), "content should not be trimmed");
  }

  @Test
  void applyPreviewIfEnabled_previewEnabled_firstPages_trimsPdfPages_andParsedContent() throws Exception {
    PdfPreparationService svc = new PdfPreparationService(new PdfBoxTextExtractor(), new PdfBoxPdfSplitter());

    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false; // deterministic
    cfg.preview.nrPages = 5;

    ParsedPDF parsedPdf = new ParsedPDF(fakePagesWithTocAndContent(), 0);
    int total = parsedPdf.getContent().size();

    List<PDDocument> pdfPages = newPdfPages(total);

    List<PDDocument> selected = invokeApplyPreviewIfEnabled(svc, cfg, parsedPdf, pdfPages);

    assertEquals(2, selected.size(), "should keep first 2 pdf pages");
    assertEquals(2, parsedPdf.getContent().size(), "should keep first 2 content pages");

    // order preserved
    assertSame(pdfPages.get(0), selected.get(0));
    assertSame(pdfPages.get(1), selected.get(1));
  }

  @Test
  void applyPreviewIfEnabled_previewEnabled_nrPagesGreaterThanTotal_clampsToTotal() throws Exception {
    PdfPreparationService svc = new PdfPreparationService(new PdfBoxTextExtractor(), new PdfBoxPdfSplitter());

    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 999;

    ParsedPDF parsedPdf = new ParsedPDF(fakePagesWithTocAndContent(), 0);
    int total = parsedPdf.getContent().size();

    List<PDDocument> pdfPages = newPdfPages(total);

    List<PDDocument> selected = invokeApplyPreviewIfEnabled(svc, cfg, parsedPdf, pdfPages);

    assertEquals(total, selected.size(), "should clamp to total");
    assertEquals(total, parsedPdf.getContent().size(), "content should clamp to total");
  }

  // -------------------------
  // Reflection helper (private method)
  // -------------------------

  @SuppressWarnings("unchecked")
  private static List<PDDocument> invokeApplyPreviewIfEnabled(
      PdfPreparationService svc,
      AppConfig cfg,
      ParsedPDF parsedPdf,
      List<PDDocument> pdfPages
  ) throws Exception {
    Method m = PdfPreparationService.class.getDeclaredMethod(
        "applyPreviewIfEnabled",
        AppConfig.class,
        ParsedPDF.class,
        List.class
    );
    m.setAccessible(true);
    return (List<PDDocument>) m.invoke(svc, cfg, parsedPdf, pdfPages);
  }

  // -------------------------
  // Test data
  // -------------------------

  private static AppConfig minimalConfig() {
    AppConfig cfg = new AppConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 5;
    return cfg;
  }

  private List<PDDocument> newPdfPages(int n) {
    List<PDDocument> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      docsToClose.add(d);
      list.add(d);
    }
    return list;
  }

  /**
   * Produces raw extracted pages that satisfy your TOC heuristics and allow ParsedPDF to build:
   * - Page 0 contains "Table of Contents" so getTableOfContentFirstPage succeeds.
   * - Page 1 looks like TOC (>= 90% lines end with a page number).
   * - Page 2 is NOT a TOC page, so getTableOfContentLastPage can stop at page 1.
   * - Subsequent pages contain the first chapter header so getStringPagesWithoutTOC can find content.
   */
  private static List<String> fakePagesWithTocAndContent() {
    String toc0 = "Table of Contents\n";

    // 100% of non-empty lines end with a number => TOC page per heuristic
    String toc1 = """
        Chapter 1: Intro 1
        Chapter 2: Basics 5
        Chapter 3: Advanced 9
        """;

    // Not a TOC page (lines don't end in numbers)
    String notToc = "Preface\nThis is not a TOC page.\nJust text.\n";

    // Content pages (must contain first chapter header somewhere)
    String content1 = "Chapter 1: Intro\nActual content begins here.\n";
    String content2 = "More content...\n";
    String content3 = "Even more content...\n";

    return List.of(toc0, toc1, notToc, content1, content2, content3);
  }
}

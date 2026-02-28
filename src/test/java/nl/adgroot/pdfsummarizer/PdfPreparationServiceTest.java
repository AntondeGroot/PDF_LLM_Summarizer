package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.pdf.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.PdfPreparationService;
import nl.adgroot.pdfsummarizer.pdf.PreparedPdf;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
  void loadAndPrepare_previewDisabled_returnsAllAlignedContentPages_andDoesNotTrimContent() throws Exception {
    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = false;

    List<String> rawPages = fakePagesWithTocAndContent();

    FakeExtractor extractor = new FakeExtractor(rawPages);

    // Splitter must mimic real behavior: one PDDocument per original page
    FakeSplitter splitter = new FakeSplitter(newPdfPages(rawPages.size()));

    PdfPreparationService svc = new PdfPreparationService(extractor, splitter);

    PreparedPdf prepared = svc.loadAndPrepare(Path.of("dummy.pdf"), cfg);

    int expectedContentSize = expectedContentSizeAfterParsedPdf(rawPages);

    assertEquals(expectedContentSize, prepared.pdfPages().size(),
        "preview disabled should keep all aligned content pages");
    assertEquals(expectedContentSize, prepared.parsed().getContent().size(),
        "content should not be trimmed");

    // Also sanity-check PdfObject indexes are 0..n-1
    for (int i = 0; i < expectedContentSize; i++) {
      assertEquals(i, prepared.pdfPages().get(i).getIndex());
    }
  }

  @Test
  void loadAndPrepare_previewEnabled_nonRandom_firstN_trimsPdfObjects_andParsedContent() throws Exception {
    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 2; // deterministic

    List<String> rawPages = fakePagesWithTocAndContent();

    FakeExtractor extractor = new FakeExtractor(rawPages);

    // Splitter returns docs for full raw PDF page count
    FakeSplitter splitter = new FakeSplitter(newPdfPages(rawPages.size()));

    PdfPreparationService svc = new PdfPreparationService(extractor, splitter);

    PreparedPdf prepared = svc.loadAndPrepare(Path.of("dummy.pdf"), cfg);

    assertEquals(2, prepared.pdfPages().size(), "should keep first 2 selected pages");
    assertEquals(2, prepared.parsed().getContent().size(), "should keep first 2 content pages");

    // Order preserved (index 0 then 1)
    assertEquals(0, prepared.pdfPages().get(0).getIndex());
    assertEquals(1, prepared.pdfPages().get(1).getIndex());
  }

  @Test
  void loadAndPrepare_previewEnabled_nrPagesGreaterThanTotal_clampsToTotal() throws Exception {
    List<String> rawPages = fakePagesWithTocAndContent();
    int totalContent = expectedContentSizeAfterParsedPdf(rawPages);

    FakeExtractor extractor = new FakeExtractor(rawPages);
    FakeSplitter splitter = new FakeSplitter(newPdfPages(rawPages.size()));

    PdfPreparationService svc = new PdfPreparationService(extractor, splitter);

    AppConfig cfg = minimalConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 999;

    PreparedPdf prepared = svc.loadAndPrepare(Path.of("dummy.pdf"), cfg);

    assertEquals(totalContent, prepared.pdfPages().size(), "should clamp to total content pages");
    assertEquals(totalContent, prepared.parsed().getContent().size(), "content should clamp to total");
  }

  // -------------------------
  // Fakes (avoid IO)
  // -------------------------

  private static final class FakeExtractor extends PdfBoxTextExtractor {
    private final List<String> pages;
    FakeExtractor(List<String> pages) { this.pages = pages; }

    @Override
    public List<String> extractPages(Path pdfPath) throws IOException {
      return pages;
    }
  }

  private final class FakeSplitter extends PdfBoxPdfSplitter {
    private final List<PDDocument> pages;
    FakeSplitter(List<PDDocument> pages) { this.pages = pages; }

    @Override
    public List<PDDocument> splitInMemory(Path pdfPath) throws IOException {
      return pages;
    }
  }

  // -------------------------
  // Helpers
  // -------------------------

  private static AppConfig minimalConfig() {
    AppConfig cfg = new AppConfig();
    cfg.cards.nrOfLinesUsedForContext = 0;
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 5;
    return cfg;
  }

  private List<PDDocument> newPdfPages(int n) {
    List<PDDocument> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage()); // real splitter returns 1-page docs
      docsToClose.add(d);
      list.add(d);
    }
    return list;
  }

  private static int expectedContentSizeAfterParsedPdf(List<String> rawPages) {
    // Use the real ParsedPDF logic so the test stays correct if heuristics change
    ParsedPDF parsed = new ParsedPDF(rawPages, 0);
    return parsed.getContent().size();
  }

  /**
   * Produces raw extracted pages that satisfy TOC heuristics and allow ParsedPDF to build:
   * - Page 0 contains "Table of Contents" so getTableOfContentFirstPage succeeds.
   * - Page 1 looks like TOC (>= 90% lines end with a page number).
   * - Page 2 is NOT a TOC page, so getTableOfContentLastPage can stop at page 1.
   * - Subsequent pages contain the first chapter header so getStringPagesWithoutTOC can find content.
   */
  private static List<String> fakePagesWithTocAndContent() {
    String toc0 = "Table of Contents\n";

    String toc1 = """
        Chapter 1: Intro 1
        Chapter 2: Basics 5
        Chapter 3: Advanced 9
        """;

    String notToc = "Preface\nThis is not a TOC page.\nJust text.\n";

    // Content pages (3 pages)
    String content1 = "Chapter 1: Intro\nActual content begins here.\n";
    String content2 = "More content...\n";
    String content3 = "Even more content...\n";

    return List.of(toc0, toc1, notToc, content1, content2, content3);
  }
}
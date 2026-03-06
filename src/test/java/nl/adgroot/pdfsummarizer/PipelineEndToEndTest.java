package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.llm.records.LlmResult;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.records.CardsPage;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreparationService;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreviewComposer;
import nl.adgroot.pdfsummarizer.pdf.parsing.PreparedPdf;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pipeline integration tests using a fake LLM.
 *
 * Test PDF layout (23 pages total, 20 content pages):
 *   p0: "Table of Contents"
 *   p1: "Chapter 1: Intro 1 / Chapter 2: Advanced 11"   ← TOC entries
 *   p2: "Preface..."                                     ← stripped by getStringPagesWithoutTOC
 *   p3: "Chapter 1: Intro\nCONTENT-0"                   ← first content page (index 0)
 *   p4..p11: "CONTENT-1".."CONTENT-8"                   ← Chapter 1 (indices 0–8, 9 pages)
 *   p12..p22: "CONTENT-9".."CONTENT-19"                 ← Chapter 2 (indices 9–19, 11 pages)
 *
 * With offset=-1 (first chapter starts at TOC page 1):
 *   Chapter 1 pdfPageNr range [0,9]  → content indices 0–8   (9 pages)
 *   Chapter 2 pdfPageNr range [10,22] → content indices 9–19 (11 pages)
 */
class PipelineEndToEndTest {

  static final String CHAPTER_1_HEADER = "Chapter 1: Intro";
  static final String CHAPTER_2_HEADER = "Chapter 2: Advanced";
  static final int CHAPTER_1_PAGES = 9;   // content indices 0–8
  static final int CHAPTER_2_PAGES = 11;  // content indices 9–19
  static final int TOTAL_CONTENT_PAGES = CHAPTER_1_PAGES + CHAPTER_2_PAGES; // 20
  static final int CARDS_PER_PAGE = 2;    // FakeLlmClient generates 2 cards per page

  private PreparedPdf lastPrepared;

  @AfterEach
  void closePdfDocuments() {
    if (lastPrepared != null) {
      for (PdfObject obj : lastPrepared.pdfPages()) {
        try { obj.getDocument().close(); } catch (Exception ignored) {}
      }
      lastPrepared = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Scenario 1: preview=true, nrPages=5, random=false → first 5 pages selected
  // ---------------------------------------------------------------------------

  @Test
  void preview_nonRandom_5pages_selectsFirstFiveAndProcessesThem() throws Exception {
    Path pdf = buildTestPdf(TOTAL_CONTENT_PAGES);

    AppConfig cfg = baseConfig();
    cfg.preview.enabled = true;
    cfg.preview.nrPages = 5;
    cfg.preview.randomPages = false;
    cfg.preview.combinePdfWithNotes = false;

    lastPrepared = preparePdf(pdf, cfg);

    assertEquals(5, lastPrepared.pdfPages().size(),
        "Preview non-random should select exactly 5 pages");

    List<CardsPage> captured = runPipeline(lastPrepared, cfg);

    // All 5 selected pages have been processed with multiple notes/cards each
    for (PdfObject p : lastPrepared.pdfPages()) {
      assertTrue(p.hasNotes(),
          "Page at index " + p.getIndex() + " should have notes");
      assertEquals(CARDS_PER_PAGE, p.getCards().size(),
          "Page at index " + p.getIndex() + " should have " + CARDS_PER_PAGE + " cards");
      assertTrue(p.getNotes().contains("Card-A for page " + p.getIndex()),
          "Notes should contain first card for page " + p.getIndex());
      assertTrue(p.getNotes().contains("Card-B for page " + p.getIndex()),
          "Notes should contain second card for page " + p.getIndex());
    }

    // Pages 0–4 are all in Chapter 1 → exactly 1 chapter file written
    assertEquals(1, captured.size(),
        "Only 1 chapter file expected when first 5 pages are all in Chapter 1");

    // Chapter file contains both cards for each of pages 0–4, in page order
    String chapterContent = captured.get(0).toString();
    assertEquals(5 * CARDS_PER_PAGE, captured.get(0).content().size(),
        "Chapter file should have " + (5 * CARDS_PER_PAGE) + " cards total (2 per page × 5 pages)");
    int[] positions = new int[5];
    for (int i = 0; i < 5; i++) {
      positions[i] = chapterContent.indexOf("Card-A for page " + i);
      assertTrue(positions[i] >= 0, "Chapter file must contain Card-A for page " + i);
    }
    for (int i = 1; i < 5; i++) {
      assertTrue(positions[i] > positions[i - 1],
          "Cards must appear in page order: page " + (i - 1) + " before page " + i);
    }

    // Confirm all 5 selected pages are in Chapter 1
    for (PdfObject p : lastPrepared.pdfPages()) {
      assertEquals(CHAPTER_1_HEADER, p.getChapter(),
          "All first-5 pages should belong to Chapter 1");
    }
  }

  // ---------------------------------------------------------------------------
  // Scenario 2: preview=true, nrPages=5, random=true → 5 random pages selected
  // ---------------------------------------------------------------------------

  @Test
  void preview_random_5pages_selectsFiveArbitraryPagesAndProcessesThem() throws Exception {
    Path pdf = buildTestPdf(TOTAL_CONTENT_PAGES);

    AppConfig cfg = baseConfig();
    cfg.preview.enabled = true;
    cfg.preview.nrPages = 5;
    cfg.preview.randomPages = true;
    cfg.preview.combinePdfWithNotes = false;

    lastPrepared = preparePdf(pdf, cfg);

    assertEquals(5, lastPrepared.pdfPages().size(),
        "Random preview should select exactly 5 pages");

    runPipeline(lastPrepared, cfg);

    List<PdfObject> pages = lastPrepared.pdfPages();

    // All 5 selected pages have been processed with multiple notes/cards each
    for (PdfObject p : pages) {
      assertTrue(p.hasNotes(),
          "Randomly selected page at index " + p.getIndex() + " should have notes");
      assertEquals(CARDS_PER_PAGE, p.getCards().size(),
          "Page at index " + p.getIndex() + " should have " + CARDS_PER_PAGE + " cards");
      assertTrue(p.getNotes().contains("Card-A for page " + p.getIndex()),
          "Notes should contain first card for page " + p.getIndex());
      assertTrue(p.getNotes().contains("Card-B for page " + p.getIndex()),
          "Notes should contain second card for page " + p.getIndex());
    }

    // Selected pages are sorted by index (PreviewSelectionService guarantees ascending order)
    for (int i = 1; i < pages.size(); i++) {
      assertTrue(pages.get(i).getIndex() > pages.get(i - 1).getIndex(),
          "Selected pages must be in ascending index order");
    }

    // All selected pages are valid content indices (0–19)
    for (PdfObject p : pages) {
      assertTrue(p.getIndex() >= 0 && p.getIndex() < TOTAL_CONTENT_PAGES,
          "Selected page index must be within valid range [0, " + (TOTAL_CONTENT_PAGES - 1) + "]");
    }
  }

  // ---------------------------------------------------------------------------
  // Scenario 3: preview=false → all 20 pages processed, both chapters written
  // ---------------------------------------------------------------------------

  @Test
  void noPreview_20pages_processesAllPagesAcrossBothChapters() throws Exception {
    Path pdf = buildTestPdf(TOTAL_CONTENT_PAGES);

    AppConfig cfg = baseConfig();
    cfg.preview.enabled = false;

    lastPrepared = preparePdf(pdf, cfg);

    assertEquals(TOTAL_CONTENT_PAGES, lastPrepared.pdfPages().size(),
        "All " + TOTAL_CONTENT_PAGES + " content pages should be prepared");

    List<CardsPage> captured = runPipeline(lastPrepared, cfg);

    // Every page has notes containing both cards, and getCards() has exactly CARDS_PER_PAGE entries
    for (PdfObject p : lastPrepared.pdfPages()) {
      assertTrue(p.hasNotes(),
          "Page " + p.getIndex() + " should have notes");
      assertEquals(CARDS_PER_PAGE, p.getCards().size(),
          "Page " + p.getIndex() + " should have " + CARDS_PER_PAGE + " cards");
      assertTrue(p.getNotes().contains("Card-A for page " + p.getIndex()),
          "Notes should contain first card for page " + p.getIndex());
      assertTrue(p.getNotes().contains("Card-B for page " + p.getIndex()),
          "Notes should contain second card for page " + p.getIndex());
    }

    // Both chapter files written
    assertEquals(2, captured.size(),
        "Expected 2 chapter files (one per chapter)");

    CardsPage ch1 = captured.stream()
        .filter(c -> c.chapter().contains("Chapter-1"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Chapter 1 file not found. Got: " + captured));

    CardsPage ch2 = captured.stream()
        .filter(c -> c.chapter().contains("Chapter-2"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Chapter 2 file not found. Got: " + captured));

    // Correct card counts per chapter (2 cards × pages per chapter)
    assertEquals(CHAPTER_1_PAGES * CARDS_PER_PAGE, ch1.content().size(),
        "Chapter 1 should have " + (CHAPTER_1_PAGES * CARDS_PER_PAGE) + " cards (" + CARDS_PER_PAGE + " per page × " + CHAPTER_1_PAGES + " pages)");
    assertEquals(CHAPTER_2_PAGES * CARDS_PER_PAGE, ch2.content().size(),
        "Chapter 2 should have " + (CHAPTER_2_PAGES * CARDS_PER_PAGE) + " cards (" + CARDS_PER_PAGE + " per page × " + CHAPTER_2_PAGES + " pages)");

    // Cards in Chapter 1 appear in page-index order (check first card of each page)
    String ch1Text = ch1.toString();
    for (int i = 0; i < CHAPTER_1_PAGES - 1; i++) {
      int posA = ch1Text.indexOf("Card-A for page " + i);
      int posB = ch1Text.indexOf("Card-A for page " + (i + 1));
      assertTrue(posA >= 0,
          "Chapter 1 should contain Card-A for page " + i);
      assertTrue(posB > posA,
          "Card-A for page " + i + " should appear before Card-A for page " + (i + 1));
    }

    // Cards in Chapter 2 appear in page-index order (check first card of each page)
    String ch2Text = ch2.toString();
    for (int i = CHAPTER_1_PAGES; i < TOTAL_CONTENT_PAGES - 1; i++) {
      int posA = ch2Text.indexOf("Card-A for page " + i);
      int posB = ch2Text.indexOf("Card-A for page " + (i + 1));
      assertTrue(posA >= 0,
          "Chapter 2 should contain Card-A for page " + i);
      assertTrue(posB > posA,
          "Card-A for page " + i + " should appear before Card-A for page " + (i + 1));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static PreparedPdf preparePdf(Path pdf, AppConfig cfg) throws Exception {
    return new PdfPreparationService(
        new PdfBoxTextExtractor(), new PdfBoxPdfSplitter()
    ).loadAndPrepare(pdf, cfg);
  }

  private static List<CardsPage> runPipeline(PreparedPdf prepared, AppConfig cfg) throws Exception {
    List<CardsPage> captured = new CopyOnWriteArrayList<>();
    NotesWriter capturingWriter = new NotesWriter() {
      @Override
      public void writeCard(Path outDir, CardsPage page) throws IOException {
        captured.add(page);
      }
    };

    List<LlmClient> llms = List.of(new FakeLlmClient());
    ServerPermitPool permitPool = new ServerPermitPool(1, 2, true);
    Path outDir = Files.createTempDirectory("e2e-test-");

    try (AppExecutors exec = AppExecutors.create(cfg)) {
      new AppRunner(
          new ChapterProcessor(),
          new PagePipeline(),
          capturingWriter,
          new PdfPreviewComposer()
      ).run(
          prepared, "Test Topic", cfg,
          llms, permitPool, exec,
          new PromptTemplate("{{content}}"),
          outDir
      );
    }

    return captured;
  }

  /**
   * Simulates an LLM: parses ===PAGE n=== blocks from the prompt and returns
   * one synthetic card per page. Runs on a background thread with a small delay
   * to exercise real async / thread-scheduling behavior.
   */
  static class FakeLlmClient implements LlmClient {

    private static final Pattern PAGE_BLOCK =
        Pattern.compile("(?s)===PAGE\\s+(\\d+)===\\s*(.*?)\\s*===END PAGE===");
    private static final Pattern CONTENT_IDX =
        Pattern.compile("CONTENT-(\\d+)");
    private static final LlmMetrics ZERO_METRICS = new LlmMetrics(0, 0, 0, 0, 0);

    @Override
    public CompletableFuture<LlmResult> generateAsync(String prompt) {
      return CompletableFuture.supplyAsync(() -> {
        try {
          Thread.sleep(5); // simulate network/LLM latency to exercise real threading
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }

        StringBuilder response = new StringBuilder();
        Matcher m = PAGE_BLOCK.matcher(prompt);
        while (m.find()) {
          int seqNr = Integer.parseInt(m.group(1));
          String content = m.group(2);
          // Identify the page by its content index ("CONTENT-N"), not the block number,
          // so the card text stays stable regardless of sequential vs. original indexing.
          Matcher cm = CONTENT_IDX.matcher(content);
          int pageIdx = cm.find() ? Integer.parseInt(cm.group(1)) : seqNr;
          response
              .append("===PAGE ").append(seqNr).append("===\n")
              .append("title: Card-A for page ").append(pageIdx).append("\n")
              .append("First note for page ").append(pageIdx).append(".\n")
              .append("---\n")
              .append("title: Card-B for page ").append(pageIdx).append("\n")
              .append("Second note for page ").append(pageIdx).append(".\n")
              .append("===END PAGE===\n");
        }

        return new LlmResult(response.toString(), ZERO_METRICS);
      });
    }

    @Override public String getName() { return "fake"; }
    @Override public String getUrl()  { return "fake://test"; }
  }

  /**
   * Builds a minimal test PDF satisfying the TOC heuristics with the structure
   * described in the class Javadoc.
   *
   * With TOC "Chapter 1: Intro 1 / Chapter 2: Advanced 11" and offset=-1:
   *   Chapter 1 → content indices 0–8  (pdfPageNr 1–9  in [0, 9])
   *   Chapter 2 → content indices 9–19 (pdfPageNr 10–20 in [10, 22])
   */
  static Path buildTestPdf(int contentPages) throws Exception {
    Path pdf = Files.createTempFile("e2e-it-", ".pdf");
    try (PDDocument doc = new PDDocument()) {
      // p0: TOC locator page
      addPage(doc, "Table of Contents");
      // p1: TOC entries — all lines end with a page number → passes isPageATableOfContentsPage
      addPage(doc, "Chapter 1: Intro 1\nChapter 2: Advanced 11");
      // p2: preface — not a chapter page, stripped by getStringPagesWithoutTOC
      addPage(doc, "Preface\nThis is not a TOC page.");
      // p3: first content page — contains chapter 1 header so getStringPagesWithoutTOC picks it
      addPage(doc, "Chapter 1: Intro\nCONTENT-0");
      // remaining content pages
      for (int i = 1; i < contentPages; i++) {
        addPage(doc, "CONTENT-" + i);
      }
      doc.save(pdf.toFile());
    }
    return pdf;
  }

  private static AppConfig baseConfig() {
    AppConfig cfg = new AppConfig();
    cfg.ollama.concurrency = 4;       // sets CPU pool size in AppExecutors
    cfg.cards.maxCardsPerChunk = 10;
    cfg.cards.nrOfLinesUsedForContext = 0;
    cfg.chunking.maxTokensPerChunk = 100_000; // large enough: all pages fit in one batch
    cfg.preview.combinePdfWithNotes = false;
    return cfg;
  }

  private static void addPage(PDDocument doc, String text) throws Exception {
    PDPage page = new PDPage();
    doc.addPage(page);
    try (PDPageContentStream cs = new PDPageContentStream(
        doc, page, PDPageContentStream.AppendMode.OVERWRITE, true, true)) {
      cs.beginText();
      cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      cs.newLineAtOffset(50, 750);
      for (String line : text.split("\n", -1)) {
        if (!line.isEmpty()) cs.showText(line);
        cs.newLineAtOffset(0, -14);
      }
      cs.endText();
    }
  }
}
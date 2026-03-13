package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.notes.records.CardsPage;
import nl.adgroot.pdfsummarizer.pdf.parsing.Chapter;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.pipeline.BatchContext;
import nl.adgroot.pdfsummarizer.pipeline.BatchPipeline;
import nl.adgroot.pdfsummarizer.pipeline.ChapterProcessor;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChapterProcessorTest {

  private final ExecutorService permitExec = Executors.newSingleThreadExecutor();
  private final ExecutorService cpuExec = Executors.newFixedThreadPool(2);
  private final ExecutorService writerExec = Executors.newSingleThreadExecutor();

  private final List<PDDocument> docsToClose = new ArrayList<>();

  @AfterEach
  void tearDown() {
    permitExec.shutdownNow();
    cpuExec.shutdownNow();
    writerExec.shutdownNow();

    for (PDDocument d : docsToClose) {
      try { d.close(); } catch (Exception ignored) {}
    }
    docsToClose.clear();
  }

  @Test
  void processChapterAsync_fillsNotesOnEachPdfObject_evenWhenAllPagesInSameChapter() throws Exception {
    // GIVEN: one chapter, 5 pages, all in the same chapter
    String chapterHeader = "Chapter 1: Intro";
    int n = 5;

    List<PdfObject> pages = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      docsToClose.add(d);

      PdfObject obj = new PdfObject(i, chapterHeader, d, "PAGE-TEXT-" + i);
      pages.add(obj);
    }

    Chapter chapter = new Chapter(chapterHeader, 1, 0);

    // Pipeline stub: returns 1 "card" per page
    BatchPipeline pipeline = new StubPipeline();

    AppConfig cfg = new AppConfig();
    cfg.cards.maxCardsPerChunk = 10;

    BatchContext ctx = new BatchContext(
        List.of(), new ServerPermitPool(1, 1, true),
        permitExec, cpuExec,
        new PromptTemplates(new PromptTemplate("{{content}}"), null, null, null),
        cfg, "Topic", new ProgressTracker(n),
        Files.createTempDirectory("chapterprocessor-test-")
    );

    // WHEN
    new ChapterProcessor().processChapterAsync(
        chapter, pages, pipeline, ctx, writerExec, new NoopNotesWriter()
    ).get(2, TimeUnit.SECONDS);

    // THEN: every page in the chapter should have notes filled
    assertEquals(n, pages.size());
    for (int i = 0; i < n; i++) {
      PdfObject p = pages.get(i);
      assertTrue(p.hasNotes(), "PdfObject " + i + " should have notes");
      assertTrue(p.getNotes().contains("Card for index=" + i),
          "Notes should contain the generated card marker for index=" + i);
    }
  }

  @Test
  void processChapterAsync_storesCardsOnPdfObject() throws Exception {
    String chapterHeader = "Chapter 2: Storage";
    int n = 3;

    List<PdfObject> pages = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      docsToClose.add(d);
      pages.add(new PdfObject(i, chapterHeader, d, "text-" + i));
    }

    Chapter chapter = new Chapter(chapterHeader, 1, 0);
    AppConfig cfg = new AppConfig();
    cfg.cards.maxCardsPerChunk = 10;

    BatchContext ctx = new BatchContext(
        List.of(), new ServerPermitPool(1, 1, true),
        permitExec, cpuExec,
        new PromptTemplates(new PromptTemplate("{{content}}"), null, null, null),
        cfg, "Topic", new ProgressTracker(n),
        Files.createTempDirectory("chapterprocessor-cards-test-")
    );

    new ChapterProcessor().processChapterAsync(
        chapter, pages, new StubPipeline(), ctx, writerExec, new NoopNotesWriter()
    ).get(2, TimeUnit.SECONDS);

    for (int i = 0; i < n; i++) {
      List<String> cards = pages.get(i).getCards();
      assertFalse(cards.isEmpty(), "PdfObject " + i + " should have cards stored");
      assertEquals(1, cards.size());
      assertEquals("Card for index=" + i, cards.getFirst());
    }
  }

  @Test
  void processChapterAsync_chapterFileAssembledFromPdfObjectCardsInOrder() throws Exception {
    String chapterHeader = "Chapter 3: Networking";
    int n = 3;

    List<PdfObject> pages = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      docsToClose.add(d);
      pages.add(new PdfObject(i, chapterHeader, d, "text-" + i));
    }

    Chapter chapter = new Chapter(chapterHeader, 1, 0);
    AppConfig cfg = new AppConfig();
    cfg.cards.maxCardsPerChunk = 10;

    BatchContext ctx = new BatchContext(
        List.of(), new ServerPermitPool(1, 1, true),
        permitExec, cpuExec,
        new PromptTemplates(new PromptTemplate("{{content}}"), null, null, null),
        cfg, "Topic", new ProgressTracker(n),
        Files.createTempDirectory("chapterprocessor-order-test-")
    );

    // Capture the CardsPage written to the chapter file
    AtomicReference<CardsPage> captured = new AtomicReference<>();
    NotesWriter capturingWriter = new NotesWriter() {
      @Override
      public void writeCard(Path outDir, CardsPage page) throws IOException {
        captured.set(page);
      }
    };

    new ChapterProcessor().processChapterAsync(
        chapter, pages, new StubPipeline(), ctx, writerExec, capturingWriter
    ).get(2, TimeUnit.SECONDS);

    CardsPage written = captured.get();
    assertTrue(written != null && written.hasContent(), "Chapter file should have content");

    String output = written.toString();
    // Cards must appear in page order: index 0, then 1, then 2
    int pos0 = output.indexOf("Card for index=0");
    int pos1 = output.indexOf("Card for index=1");
    int pos2 = output.indexOf("Card for index=2");
    assertTrue(pos0 >= 0 && pos1 > pos0 && pos2 > pos1,
        "Cards should appear in page order in the chapter output");
  }

  // ----------------------------
  // Batching tests
  // ----------------------------

  // Token estimate used by ChapterProcessor: ceil(length / 4)
  private static String textOfTokens(int tokens) {
    return "x".repeat(tokens * 4);
  }

  private AppConfig batchingCfg(boolean localBatching, int maxTokensPerChunk) {
    AppConfig cfg = new AppConfig();
    cfg.ollama.localBatching = localBatching;
    cfg.chunking.maxTokensPerChunk = maxTokensPerChunk;
    cfg.cards.maxCardsPerChunk = 10;
    return cfg;
  }

  private List<PdfObject> pagesInChapter(String chapter, int count, int tokensEach) {
    List<PdfObject> pages = new ArrayList<>();
    String text = textOfTokens(tokensEach);
    for (int i = 0; i < count; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      docsToClose.add(d);
      pages.add(new PdfObject(i, chapter, d, text));
    }
    return pages;
  }

  private List<List<Integer>> runAndCaptureBatches(
      List<PdfObject> pages, String chapter, AppConfig cfg) throws Exception {

    CapturingPipeline capturing = new CapturingPipeline();

    BatchContext ctx = new BatchContext(
        List.of(), new ServerPermitPool(1, 1, true),
        permitExec, cpuExec,
        new PromptTemplates(new PromptTemplate(""), null, null, null),
        cfg, "Topic", new ProgressTracker(pages.size()),
        Files.createTempDirectory("batching-test-")
    );

    new ChapterProcessor().processChapterAsync(
        new Chapter(chapter, 1, 0), pages, capturing, ctx,
        writerExec, new NoopNotesWriter()
    ).get(2, TimeUnit.SECONDS);

    return capturing.batches;
  }

  @Test
  void batching_localBatchingDisabled_eachPageIsItsOwnBatch() throws Exception {
    String chapter = "Ch";
    List<PdfObject> pages = pagesInChapter(chapter, 3, 100);
    AppConfig cfg = batchingCfg(false, 99999);

    List<List<Integer>> batches = runAndCaptureBatches(pages, chapter, cfg);

    assertEquals(3, batches.size(), "Expected one batch per page");
    batches.forEach(b -> assertEquals(1, b.size()));
  }

  @Test
  void batching_allPagesFitInOneChunk_producesOneBatch() throws Exception {
    String chapter = "Ch";
    // 3 pages × 25 tokens = 75 total, well within max=200
    List<PdfObject> pages = pagesInChapter(chapter, 3, 25);
    AppConfig cfg = batchingCfg(true, 200);

    List<List<Integer>> batches = runAndCaptureBatches(pages, chapter, cfg);

    assertEquals(1, batches.size(), "All pages should fit in one batch");
    assertEquals(3, batches.getFirst().size());
  }

  @Test
  void batching_pagesOverflowChunk_splitCorrectly() throws Exception {
    String chapter = "Ch";
    // 3 pages × 100 tokens, max=250: pages 0+1 fit (200 ≤ 250), page 2 overflows → [0,1] + [2]
    List<PdfObject> pages = pagesInChapter(chapter, 3, 100);
    AppConfig cfg = batchingCfg(true, 250);

    List<List<Integer>> batches = runAndCaptureBatches(pages, chapter, cfg);

    assertEquals(2, batches.size());
    assertEquals(2, batches.get(0).size(), "First batch should contain 2 pages");
    assertEquals(1, batches.get(1).size(), "Second batch should contain 1 page");
  }

  @Test
  void batching_singlePageExceedsMaxTokens_isStillIncludedAlone() throws Exception {
    String chapter = "Ch";
    // 1 page of 200 tokens, max=50 — must not be dropped
    List<PdfObject> pages = pagesInChapter(chapter, 1, 200);
    AppConfig cfg = batchingCfg(true, 50);

    List<List<Integer>> batches = runAndCaptureBatches(pages, chapter, cfg);

    assertEquals(1, batches.size(), "Oversized page must still form a batch");
    assertEquals(1, batches.getFirst().size());
  }

  @Test
  void batching_emptyChapter_producesNoBatches() throws Exception {
    String chapter = "Ch";
    List<PdfObject> allPages = pagesInChapter("Other chapter", 3, 50);
    AppConfig cfg = batchingCfg(true, 200);

    List<List<Integer>> batches = runAndCaptureBatches(allPages, chapter, cfg);

    assertTrue(batches.isEmpty(), "Chapter with no matching pages should produce no batches");
  }

  @Test
  void batching_pagesPreserveOrderAcrossBatches() throws Exception {
    String chapter = "Ch";
    // 4 pages of 100 tokens, max=250: [0,1] + [2,3]
    List<PdfObject> pages = pagesInChapter(chapter, 4, 100);
    AppConfig cfg = batchingCfg(true, 250);

    List<List<Integer>> batches = runAndCaptureBatches(pages, chapter, cfg);

    assertEquals(2, batches.size());
    assertEquals(List.of(0, 1), batches.get(0), "First batch should contain pages 0 and 1 in order");
    assertEquals(List.of(2, 3), batches.get(1), "Second batch should contain pages 2 and 3 in order");
  }

  // ----------------------------
  // Stubs
  // ----------------------------

  /** A pipeline stub that returns a deterministic result without calling LLMs. */
  static class StubPipeline implements BatchPipeline {

    @Override
    public CompletableFuture<Map<Integer, List<String>>> processBatchAsync(
        BatchContext ctx, String chapterTitle, List<PdfObject> batch
    ) {
      Map<Integer, List<String>> out = new java.util.HashMap<>();
      for (var p : batch) {
        out.put(p.getIndex(), List.of("Card for index=" + p.getIndex()));
      }
      return CompletableFuture.completedFuture(out);
    }
  }

  /** Records the index list of each batch received, for batching assertions. */
  static class CapturingPipeline implements BatchPipeline {
    final List<List<Integer>> batches = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public CompletableFuture<Map<Integer, List<String>>> processBatchAsync(
        BatchContext ctx, String chapterTitle, List<PdfObject> batch
    ) {
      batches.add(batch.stream().map(PdfObject::getIndex).toList());
      Map<Integer, List<String>> out = new java.util.HashMap<>();
      for (var p : batch) out.put(p.getIndex(), List.of("Card for index=" + p.getIndex()));
      return CompletableFuture.completedFuture(out);
    }
  }

  /** Writer that does nothing (so tests don't touch filesystem). */
  static class NoopNotesWriter extends NotesWriter {
    @Override
    public void writeCard(Path outDir, nl.adgroot.pdfsummarizer.notes.records.CardsPage page) {
      // no-op
    }
  }
}

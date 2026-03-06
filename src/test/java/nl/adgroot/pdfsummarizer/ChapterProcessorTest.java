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
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.notes.records.CardsPage;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Chapter;
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
    PagePipeline pipeline = new StubPipeline();

    // Other deps (mostly unused by the stub pipeline)
    List<LlmClient> llms = List.of(); // not used by StubPipeline
    ServerPermitPool permits = new ServerPermitPool(1, 1, true);

    PromptTemplate template = new PromptTemplate("{{content}}");
    AppConfig cfg = new AppConfig();
    cfg.cards.maxCardsPerChunk = 10;

    ProgressTracker tracker = new ProgressTracker(n);

    NotesWriter writer = new NoopNotesWriter();
    Path outDir = Files.createTempDirectory("chapterprocessor-test-");

    ChapterProcessor processor = new ChapterProcessor();

    // WHEN
    processor.processChapterAsync(
        chapter,
        pages,
        "Topic",
        pipeline,
        llms,
        permits,
        permitExec,
        cpuExec,
        writerExec,
        template,
        cfg,
        tracker,
        writer,
        outDir
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
    PagePipeline pipeline = new StubPipeline();
    ServerPermitPool permits = new ServerPermitPool(1, 1, true);
    PromptTemplate template = new PromptTemplate("{{content}}");
    AppConfig cfg = new AppConfig();
    cfg.cards.maxCardsPerChunk = 10;
    ProgressTracker tracker = new ProgressTracker(n);

    new ChapterProcessor().processChapterAsync(
        chapter, pages, "Topic", pipeline,
        List.of(), permits, permitExec, cpuExec, writerExec,
        template, cfg, tracker, new NoopNotesWriter(),
        Files.createTempDirectory("chapterprocessor-cards-test-")
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
    PagePipeline pipeline = new StubPipeline();
    ServerPermitPool permits = new ServerPermitPool(1, 1, true);
    PromptTemplate template = new PromptTemplate("{{content}}");
    AppConfig cfg = new AppConfig();
    cfg.cards.maxCardsPerChunk = 10;
    ProgressTracker tracker = new ProgressTracker(n);

    // Capture the CardsPage written to the chapter file
    AtomicReference<CardsPage> captured = new AtomicReference<>();
    NotesWriter capturingWriter = new NotesWriter() {
      @Override
      public void writeCard(Path outDir, CardsPage page) throws IOException {
        captured.set(page);
      }
    };

    new ChapterProcessor().processChapterAsync(
        chapter, pages, "Topic", pipeline,
        List.of(), permits, permitExec, cpuExec, writerExec,
        template, cfg, tracker, capturingWriter,
        Files.createTempDirectory("chapterprocessor-order-test-")
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
  // Stubs
  // ----------------------------

  /** A pipeline that returns a deterministic PageResult without calling LLMs. */
  static class StubPipeline extends PagePipeline {

    @Override
    public CompletableFuture<Map<Integer, List<String>>> processBatchAsync(
        List<LlmClient> llms,
        ServerPermitPool permits,
        ExecutorService permitPoolExecutor,
        ExecutorService cpuPoolExecutor,
        PromptTemplate promptTemplate,
        AppConfig cfg,
        String topic,
        String chapterTitle,
        List<PdfObject> batch,
        ProgressTracker tracker
    ) {
      // Return one "card" per PdfObject, keyed by PdfObject.index
      Map<Integer, List<String>> out = new java.util.HashMap<>();
      for (var p : batch) {
        out.put(p.getIndex(), List.of("Card for index=" + p.getIndex()));
      }
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
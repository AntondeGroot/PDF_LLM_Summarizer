package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.PdfObject;
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
    List<OllamaClient> llms = List.of(); // not used by StubPipeline
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

  // ----------------------------
  // Stubs
  // ----------------------------

  /** A pipeline that returns a deterministic PageResult without calling LLMs. */
  static class StubPipeline extends PagePipeline {
    @Override
    public CompletableFuture<PageResult> processPageAsync(
        List<OllamaClient> llms,
        ServerPermitPool permits,
        ExecutorService permitPoolExecutor,
        ExecutorService cpuPoolExecutor,
        PromptTemplate promptTemplate,
        AppConfig cfg,
        String topic,
        String chapterTitle,
        int pageIndexInChapter,
        int pageNr,
        int chunkCount,
        String pageText,
        ProgressTracker tracker
    ) {
      // One "card" per page. Use index to make verification stable.
      List<String> cards = List.of("Card for index=" + pageIndexInChapter);
      return CompletableFuture.completedFuture(
          new PageResult(pageIndexInChapter, pageNr, cards, 1, new LlmMetrics(0, 0, 0, 0, 0))
      );
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
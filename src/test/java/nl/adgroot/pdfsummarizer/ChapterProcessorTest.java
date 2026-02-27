package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.notes.CardsPage;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Chapter;
import nl.adgroot.pdfsummarizer.text.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChapterProcessorTest {

  private final ExecutorService permitExec = Executors.newSingleThreadExecutor();
  private final ExecutorService cpuExec = Executors.newFixedThreadPool(2);
  private final ExecutorService writerExec = Executors.newSingleThreadExecutor();

  @AfterEach
  void tearDown() {
    permitExec.shutdownNow();
    cpuExec.shutdownNow();
    writerExec.shutdownNow();
  }

  @Test
  void processChapterAsync_previewMap_hasEntryPerPage_evenWhenAllPagesInSameChapter() throws Exception {
    // GIVEN: one chapter, 5 pages, all in the same chapter
    String chapterHeader = "Chapter 1: Intro";
    int n = 5;

    ParsedPDF parsedPdf = new ParsedPDF(fakePagesWithTocAndContent(chapterHeader), 0);

    // Override content with exactly 5 pages in the same chapter
    List<Page> content = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      content.add(newPage(chapterHeader, 100 + i, "content " + i));
    }
    parsedPdf.setContent(content);

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

    Map<Integer, CardsPage> previewMap = new ConcurrentHashMap<>();

    // Avoid writing files
    NotesWriter writer = new NoopNotesWriter();
    Path outDir = Files.createTempDirectory("chapterprocessor-test-");

    ChapterProcessor processor = new ChapterProcessor();

    // WHEN
    processor.processChapterAsync(
        chapter,
        parsedPdf,
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
        outDir,
        previewMap
    ).get(2, TimeUnit.SECONDS);

    // THEN
    // ✅ With the FIX: previewMap has 5 entries (one per selected page index)
    // ❌ Without the FIX: previewMap typically has only 1 entry (one per chapter)
    assertEquals(n, previewMap.size(),
        "Preview map should contain one CardsPage per selected page (index 0..n-1)");
    for (int i = 0; i < n; i++) {
      assertTrue(previewMap.containsKey(i), "Missing preview notes for selected page index " + i);
      assertTrue(previewMap.get(i).hasContent(), "Preview page " + i + " should have content");
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
        Page page,
        ProgressTracker tracker
    ) {
      // One "card" per page
      List<String> cards = List.of("Card for pageNr=" + pageNr);
      return CompletableFuture.completedFuture(
          new PageResult(pageIndexInChapter, pageNr, cards, 1, new LlmMetrics(0,0,0,0,0))
      );
    }
  }

  /** Writer that does nothing (so tests don't touch filesystem). */
  static class NoopNotesWriter extends NotesWriter {
    @Override
    public void writeCard(Path outDir, CardsPage page) {
      // no-op
    }
  }

  // ----------------------------
  // Helpers
  // ----------------------------

  private static List<String> fakePagesWithTocAndContent(String chapterHeader) {
    // Enough to satisfy ParsedPDF's TOC detection
    String toc0 = "Table of Contents\n";
    String toc1 = """
        %s 1
        Chapter 2: Something 5
        """.formatted(chapterHeader);
    String notToc = "Preface\nNot a TOC.\n";

    // Must contain first chapter header somewhere so content extraction can work
    String content1 = chapterHeader + "\ncontent...\n";
    return List.of(toc0, toc1, notToc, content1);
  }

  private static Page newPage(String chapter, int pageNr, String text) {
    // If your Page has a constructor, prefer it here.
    // Adjust to your real Page API if needed.
    Page p = new Page(text);
    p.chapter = chapter;
    p.pageNr = pageNr;
    return p;
  }
}
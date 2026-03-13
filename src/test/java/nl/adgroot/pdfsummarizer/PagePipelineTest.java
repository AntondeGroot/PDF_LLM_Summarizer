package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.llm.records.LlmResult;
import nl.adgroot.pdfsummarizer.notes.DefaultCardsParser;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;
import org.junit.jupiter.api.Test;

class PagePipelineTest {

  private static final LlmMetrics ZERO_METRICS = new LlmMetrics(0, 0, 0, 0, 0);

  private static PdfObject page(int index, String text) {
    return new PdfObject(index, "chapter", null, text);
  }

  // ── splitPageBlocks ──────────────────────────────────────────────────────

  @Test
  void splitPageBlocks_null_returnsEmptyMap() {
    assertTrue(PagePipeline.splitPageBlocks(null).isEmpty());
  }

  @Test
  void splitPageBlocks_blank_returnsEmptyMap() {
    assertTrue(PagePipeline.splitPageBlocks("   ").isEmpty());
  }

  @Test
  void splitPageBlocks_singleBlock_returnsContentKeyedByPageNumber() {
    String md = "===PAGE 1===\nhello world\n===END PAGE===";
    Map<Integer, String> result = PagePipeline.splitPageBlocks(md);
    assertEquals(1, result.size());
    assertEquals("hello world", result.get(1));
  }

  @Test
  void splitPageBlocks_multipleBlocks_returnsAllEntries() {
    String md = """
        ===PAGE 1===
        content one
        ===END PAGE===
        ===PAGE 2===
        content two
        ===END PAGE===
        """;
    Map<Integer, String> result = PagePipeline.splitPageBlocks(md);
    assertEquals(2, result.size());
    assertEquals("content one", result.get(1));
    assertEquals("content two", result.get(2));
  }

  @Test
  void splitPageBlocks_bodyWhitespaceIsTrimmed() {
    String md = "===PAGE 3===\n  trimmed  \n===END PAGE===";
    assertEquals("trimmed", PagePipeline.splitPageBlocks(md).get(3));
  }

  @Test
  void splitPageBlocks_noMatchingBlocks_returnsEmptyMap() {
    assertTrue(PagePipeline.splitPageBlocks("no blocks here").isEmpty());
  }

  // ── renderBatchContent ───────────────────────────────────────────────────

  @Test
  void renderBatchContent_singlePage_wrapsInPageBlock() {
    String result = PagePipeline.renderBatchContent(List.of(page(0, "some text")));
    assertTrue(result.contains("===PAGE 1==="));
    assertTrue(result.contains("some text"));
    assertTrue(result.contains("===END PAGE==="));
  }

  @Test
  void renderBatchContent_multiplePages_numberedSequentially() {
    List<PdfObject> batch = List.of(page(5, "A"), page(9, "B"), page(12, "C"));
    String result = PagePipeline.renderBatchContent(batch);
    assertTrue(result.contains("===PAGE 1==="));
    assertTrue(result.contains("===PAGE 2==="));
    assertTrue(result.contains("===PAGE 3==="));
  }

  @Test
  void renderBatchContent_nullText_treatedAsEmpty() {
    String result = PagePipeline.renderBatchContent(List.of(page(0, null)));
    assertTrue(result.contains("===PAGE 1==="));
    assertTrue(result.contains("===END PAGE==="));
  }

  @Test
  void renderBatchContent_textWithoutTrailingNewline_addsNewline() {
    String result = PagePipeline.renderBatchContent(List.of(page(0, "no newline")));
    assertTrue(result.contains("no newline\n===END PAGE==="));
  }

  @Test
  void renderBatchContent_textWithTrailingNewline_doesNotDoubleNewline() {
    String result = PagePipeline.renderBatchContent(List.of(page(0, "has newline\n")));
    assertTrue(result.contains("has newline\n===END PAGE==="));
  }

  // ── parseCards ───────────────────────────────────────────────────────────

  @Test
  void parseCards_singlePage_keyedByPdfObjectIndex() {
    // PdfObject has index=7 but is sequential position 1 in the batch
    PdfObject p = page(7, "text");
    String md = "===PAGE 1===\nQuestion?\n?\nAnswer.\n===END PAGE===";

    Map<Integer, List<String>> result = PagePipeline.parseCards(md, List.of(p), new DefaultCardsParser());

    assertTrue(result.containsKey(7), "Result must be keyed by PdfObject.index, not sequential number");
    assertEquals(1, result.get(7).size());
  }

  @Test
  void parseCards_multiplePages_eachKeyedByOwnIndex() {
    List<PdfObject> batch = List.of(page(3, ""), page(8, ""));
    String md = """
        ===PAGE 1===
        Q one?
        ?
        A one.
        ===END PAGE===
        ===PAGE 2===
        Q two?
        ?
        A two.
        ===END PAGE===
        """;

    Map<Integer, List<String>> result = PagePipeline.parseCards(md, batch, new DefaultCardsParser());

    assertEquals(1, result.get(3).size(), "Page at index 3 should have 1 card");
    assertEquals(1, result.get(8).size(), "Page at index 8 should have 1 card");
  }

  @Test
  void parseCards_noResponseForPage_returnsEmptyListForThatPage() {
    PdfObject p = page(2, "text");
    // LLM returned no block for this page
    Map<Integer, List<String>> result = PagePipeline.parseCards("", List.of(p), new DefaultCardsParser());

    assertTrue(result.containsKey(2));
    assertTrue(result.get(2).isEmpty());
  }

  // ── processBatchAsync ────────────────────────────────────────────────────

  @Test
  void processBatchAsync_singlePage_returnsCardsKeyedByIndex() throws Exception {
    PdfObject p = page(42, "content");
    String llmResponse = "===PAGE 1===\nWhat is 42?\n?\nThe answer.\n===END PAGE===";

    Map<Integer, List<String>> result = runPipeline(List.of(p), llmResponse);

    assertTrue(result.containsKey(42));
    assertEquals(1, result.get(42).size());
  }

  @Test
  void processBatchAsync_multiplePages_allPagesPresent() throws Exception {
    List<PdfObject> batch = List.of(page(1, "text A"), page(2, "text B"));
    String llmResponse = """
        ===PAGE 1===
        Q1?
        ?
        A1.
        ===END PAGE===
        ===PAGE 2===
        Q2?
        ?
        A2.
        ===END PAGE===
        """;

    Map<Integer, List<String>> result = runPipeline(batch, llmResponse);

    assertEquals(1, result.get(1).size());
    assertEquals(1, result.get(2).size());
  }

  @Test
  void processBatchAsync_emptyLlmResponse_returnsEmptyCardsForAllPages() throws Exception {
    List<PdfObject> batch = List.of(page(0, "text"), page(1, "text"));
    Map<Integer, List<String>> result = runPipeline(batch, "");

    assertTrue(result.get(0).isEmpty());
    assertTrue(result.get(1).isEmpty());
  }

  @Test
  void processBatchAsync_trackerFinishBatchCalledWithBatchSize() throws Exception {
    List<PdfObject> batch = List.of(page(0, "A"), page(1, "B"), page(2, "C"));
    ProgressTracker tracker = new ProgressTracker(3);

    runPipeline(batch, "", tracker);

    // After the batch completes, 3 pages should be counted as done
    assertTrue(tracker.formatStatus(0).startsWith("Page 3/3"));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private Map<Integer, List<String>> runPipeline(List<PdfObject> batch, String llmResponse) throws Exception {
    return runPipeline(batch, llmResponse, new ProgressTracker(batch.size()));
  }

  private Map<Integer, List<String>> runPipeline(
      List<PdfObject> batch, String llmResponse, ProgressTracker tracker) throws Exception {

    LlmClient stub = new LlmClient() {
      @Override public CompletableFuture<LlmResult> generateAsync(String prompt) {
        return CompletableFuture.completedFuture(new LlmResult(llmResponse, ZERO_METRICS));
      }
      @Override public String getName() { return "stub"; }
      @Override public String getUrl()  { return "stub://test"; }
    };

    AppConfig cfg = new AppConfig();
    cfg.cards.maxCardsPerChunk = 10;

    return new PagePipeline()
        .processBatchAsync(
            List.of(stub),
            new ServerPermitPool(1, 1, true),
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor(),
            new PromptTemplates(new PromptTemplate("{{content}}"), null, null, null),
            cfg,
            "topic",
            "chapter",
            batch,
            tracker,
            Files.createTempDirectory("pagepipeline-test-")
        ).get();
  }
}
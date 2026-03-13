package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.llm.records.LlmResult;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.pipeline.ThreeStagePagePipeline;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;
import org.junit.jupiter.api.Test;

class ThreeStagePagePipelineTest {

  private static final LlmMetrics ZERO_METRICS = new LlmMetrics(0, 0, 0, 0, 0);

  private static PdfObject page(int index, String text) {
    return new PdfObject(index, "chapter", null, text);
  }

  /** Stub that returns a different response on each successive call. */
  private static LlmClient sequentialStub(String... responses) {
    AtomicInteger call = new AtomicInteger(0);
    return new LlmClient() {
      @Override
      public CompletableFuture<LlmResult> generateAsync(String prompt) {
        int i = call.getAndIncrement();
        String response = i < responses.length ? responses[i] : "";
        return CompletableFuture.completedFuture(new LlmResult(response, ZERO_METRICS));
      }
      @Override public String getName() { return "stub"; }
      @Override public String getUrl()  { return "stub://test"; }
    };
  }

  private static PromptTemplates threeStagePrompts() {
    return new PromptTemplates(
        null,
        new PromptTemplate("STEP1"),
        new PromptTemplate("STEP2"),
        new PromptTemplate("STEP3")
    );
  }

  private Map<Integer, List<String>> runPipeline(
      List<PdfObject> batch, LlmClient stub, ProgressTracker tracker, Path outDir) throws Exception {

    AppConfig cfg = new AppConfig();
    cfg.cards.maxConceptsPerPage = 5;

    return new ThreeStagePagePipeline()
        .processBatchAsync(
            List.of(stub),
            new ServerPermitPool(1, 1, true),
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor(),
            threeStagePrompts(),
            cfg,
            "topic",
            "chapter",
            batch,
            tracker,
            outDir
        ).get();
  }

  // ── Three LLM calls per batch ────────────────────────────────────────────

  @Test
  void processBatchAsync_makesExactlyThreeLlmCalls() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    LlmClient counting = new LlmClient() {
      @Override
      public CompletableFuture<LlmResult> generateAsync(String prompt) {
        callCount.incrementAndGet();
        return CompletableFuture.completedFuture(new LlmResult("", ZERO_METRICS));
      }
      @Override public String getName() { return "stub"; }
      @Override public String getUrl()  { return "stub://test"; }
    };

    runPipeline(List.of(page(0, "text")), counting,
        new ProgressTracker(1), Files.createTempDirectory("3stage-"));

    assertEquals(3, callCount.get(), "Exactly 3 LLM calls expected (one per stage)");
  }

  // ── Cards come from step 3 response ─────────────────────────────────────

  @Test
  void processBatchAsync_returnsCardsFromStep3Response() throws Exception {
    String step3Response = """
        ===PAGE 1===
        Refined question?
        ?
        Refined answer.
        ===END PAGE===
        """;

    LlmClient stub = sequentialStub("concepts", "raw cards", step3Response);
    List<PdfObject> batch = List.of(page(7, "text"));

    Map<Integer, List<String>> result = runPipeline(
        batch, stub, new ProgressTracker(1), Files.createTempDirectory("3stage-"));

    assertTrue(result.containsKey(7));
    assertEquals(1, result.get(7).size());
    assertTrue(result.get(7).getFirst().contains("Refined question?"));
  }

  @Test
  void processBatchAsync_multiplePages_allKeyedByPdfObjectIndex() throws Exception {
    String step3Response = """
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

    LlmClient stub = sequentialStub("concepts", "raw cards", step3Response);
    List<PdfObject> batch = List.of(page(3, "A"), page(9, "B"));

    Map<Integer, List<String>> result = runPipeline(
        batch, stub, new ProgressTracker(2), Files.createTempDirectory("3stage-"));

    assertEquals(1, result.get(3).size(), "Page index 3 should have 1 card");
    assertEquals(1, result.get(9).size(), "Page index 9 should have 1 card");
  }

  @Test
  void processBatchAsync_emptyStep3Response_returnsEmptyCardsForEachPage() throws Exception {
    LlmClient stub = sequentialStub("concepts", "raw cards", "");
    List<PdfObject> batch = List.of(page(0, "text"), page(1, "text"));

    Map<Integer, List<String>> result = runPipeline(
        batch, stub, new ProgressTracker(2), Files.createTempDirectory("3stage-"));

    assertTrue(result.get(0).isEmpty());
    assertTrue(result.get(1).isEmpty());
  }

  // ── finishBatch called once after step 3 ────────────────────────────────

  @Test
  void processBatchAsync_finishBatchCalledOnceWithBatchSize() throws Exception {
    LlmClient stub = sequentialStub("concepts", "raw cards", "");
    List<PdfObject> batch = List.of(page(0, "A"), page(1, "B"), page(2, "C"));
    ProgressTracker tracker = new ProgressTracker(3);

    runPipeline(batch, stub, tracker, Files.createTempDirectory("3stage-"));

    assertTrue(tracker.formatStatus(0).startsWith("Page 3/3"),
        "tracker should count all 3 pages done after batch completes");
  }

  // ── Debug files written ──────────────────────────────────────────────────

  @Test
  void processBatchAsync_writesStep1AndStep2DebugFiles() throws Exception {
    LlmClient stub = sequentialStub("extracted concepts", "generated cards", "");
    Path outDir = Files.createTempDirectory("3stage-debug-");

    runPipeline(List.of(page(0, "text")), stub, new ProgressTracker(1), outDir);

    Path debugDir = outDir.resolve("debug");
    assertTrue(Files.exists(debugDir), "debug/ directory should be created");
    assertTrue(Files.list(debugDir).anyMatch(p -> p.getFileName().toString().startsWith("step1_")),
        "step1 debug file should be written");
    assertTrue(Files.list(debugDir).anyMatch(p -> p.getFileName().toString().startsWith("step2_")),
        "step2 debug file should be written");
  }

  @Test
  void processBatchAsync_debugFilesContainLlmOutput() throws Exception {
    LlmClient stub = sequentialStub("CONCEPTS_OUTPUT", "CARDS_OUTPUT", "");
    Path outDir = Files.createTempDirectory("3stage-debug-content-");

    runPipeline(List.of(page(0, "text")), stub, new ProgressTracker(1), outDir);

    Path debugDir = outDir.resolve("debug");
    String step1Content = Files.list(debugDir)
        .filter(p -> p.getFileName().toString().startsWith("step1_"))
        .findFirst().map(p -> { try { return Files.readString(p); } catch (Exception e) { return ""; } })
        .orElse("");
    String step2Content = Files.list(debugDir)
        .filter(p -> p.getFileName().toString().startsWith("step2_"))
        .findFirst().map(p -> { try { return Files.readString(p); } catch (Exception e) { return ""; } })
        .orElse("");

    assertTrue(step1Content.contains("CONCEPTS_OUTPUT"), "step1 debug file should contain step 1 LLM output");
    assertTrue(step2Content.contains("CARDS_OUTPUT"), "step2 debug file should contain step 2 LLM output");
  }
}
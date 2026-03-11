package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.CardsParser;
import nl.adgroot.pdfsummarizer.notes.DefaultCardsParser;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.notes.records.Card;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;

import static nl.adgroot.pdfsummarizer.notes.NotesWriter.safeFileName;

/**
 * Three-stage local LLM pipeline:
 *   Step 1 — Concept extraction  (prompt_step1_concepts.txt)
 *   Step 2 — Flashcard generation (prompt_step2_cards.txt)
 *   Step 3 — Card refinement + deduplication (prompt_step3_refine.txt)
 *
 * Intermediate outputs (concepts and raw cards) are saved under
 * <outDir>/debug/ for grading / prompt tuning.
 */
public class ThreeStagePagePipeline implements BatchPipeline {

  private static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);

  private final CardsParser cardsParser;

  private static final AppLogger log = AppLogger.getLogger(ThreeStagePagePipeline.class);

  public ThreeStagePagePipeline() {
    this(new DefaultCardsParser());
  }

  public ThreeStagePagePipeline(CardsParser cardsParser) {
    this.cardsParser = cardsParser;
  }

  @Override
  public CompletableFuture<Map<Integer, List<String>>> processBatchAsync(
      List<LlmClient> llms,
      ServerPermitPool permits,
      ExecutorService permitPoolExecutor,
      ExecutorService cpuPoolExecutor,
      PromptTemplates prompts,
      AppConfig cfg,
      String topic,
      String chapterTitle,
      List<PdfObject> batch,
      ProgressTracker tracker,
      Path outDir
  ) {
    long startNs = System.nanoTime();
    int nowInflight = IN_FLIGHT.incrementAndGet();

    String batchContent = PagePipeline.renderBatchContent(batch);

    String step1Prompt = prompts.step1().render(Map.of(
        "topic", topic,
        "section", chapterTitle,
        "maxConcepts", String.valueOf(cfg.cards.maxConceptsPerPage),
        "content", batchContent
    ));

    return permits.acquireAnyAsync(permitPoolExecutor)
        .thenCompose(serverIndex -> {
          LlmClient llm = llms.get(serverIndex);

          log.info("START 3-STAGE BATCH pages=%d chapter='%s' inflight=%d server=%d url=%s%n",
              batch.size(), chapterTitle, nowInflight, serverIndex, llm.getUrl());

          // ── Step 1: extract concepts ──────────────────────────────────────
          return llm.generateAsync(step1Prompt)
              .thenComposeAsync(step1Result -> {
                String concepts = step1Result.response();
                tracker.finishPage(step1Result.metrics());
                logStep(1, chapterTitle, batch.size());
                appendDebugFile(outDir, "step1_concepts", chapterTitle, concepts);

                String step2Prompt = prompts.step2().render(Map.of(
                    "topic", topic,
                    "section", chapterTitle,
                    "concepts", concepts
                ));

                // ── Step 2: generate cards from concepts ──────────────────
                return llm.generateAsync(step2Prompt);
              }, cpuPoolExecutor)

              .thenComposeAsync(step2Result -> {
                String rawCards = step2Result.response();
                tracker.finishPage(step2Result.metrics());
                logStep(2, chapterTitle, batch.size());
                appendDebugFile(outDir, "step2_cards", chapterTitle, rawCards);

                String step3Prompt = prompts.step3().render(Map.of(
                    "topic", topic,
                    "cards", rawCards
                ));

                // ── Step 3: refine + deduplicate ──────────────────────────
                return llm.generateAsync(step3Prompt);
              }, cpuPoolExecutor)

              .thenApplyAsync(step3Result -> {
                try {
                  String refined = step3Result.response();
                  tracker.finishPage(step3Result.metrics());
                  logStep(3, chapterTitle, batch.size());

                  Map<Integer, String> pageMd = PagePipeline.splitPageBlocks(refined);
                  Map<Integer, List<String>> result = new HashMap<>();

                  for (int i = 0; i < batch.size(); i++) {
                    PdfObject p = batch.get(i);
                    String perPage = pageMd.getOrDefault(i + 1, "");

                    List<Card> cards = cardsParser.parse(perPage);
                    List<String> cardStrings = new ArrayList<>(cards.size());
                    for (Card c : cards) cardStrings.add(c.toString());

                    result.put(p.getIndex(), cardStrings);
                  }

                  return result;
                } finally {
                  permits.release(serverIndex);
                }
              }, cpuPoolExecutor)

              .whenComplete((res, ex) -> {
                int leftInflight = IN_FLIGHT.decrementAndGet();
                long seconds = (System.nanoTime() - startNs) / 1_000_000_000;
                log.info("END   3-STAGE BATCH pages=%d chapter='%s' took=%ds inflight=%d server=%d %s%n",
                    batch.size(), chapterTitle, seconds, leftInflight, serverIndex,
                    (ex != null ? "ERROR=" + ex : ""));
              });
        });
  }

  private static void logStep(int step, String chapter, int pages) {
    log.debug("  STEP %d done chapter='%s' pages=%d%n", step, chapter, pages);
  }

  /**
   * Appends the LLM output for one batch to a per-chapter debug file.
   * Files land in <outDir>/debug/  and are safe to open in any text editor.
   */
  private static void appendDebugFile(Path outDir, String prefix, String chapterTitle, String content) {
    try {
      Path debugDir = outDir.resolve("debug");
      Files.createDirectories(debugDir);
      Path file = debugDir.resolve(prefix + "_" + safeFileName(chapterTitle) + ".md");
      Files.writeString(
          file,
          "\n\n=== BATCH ===\n\n" + content,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      log.error("Warning: could not write debug file: " + e.getMessage());
    }
  }
}
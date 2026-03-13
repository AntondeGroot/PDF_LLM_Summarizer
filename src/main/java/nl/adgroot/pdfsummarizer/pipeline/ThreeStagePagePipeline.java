package nl.adgroot.pdfsummarizer.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import nl.adgroot.pdfsummarizer.AppLogger;
import nl.adgroot.pdfsummarizer.notes.CardsParser;
import nl.adgroot.pdfsummarizer.notes.DefaultCardsParser;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;

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
      BatchContext ctx,
      String chapterTitle,
      List<PdfObject> batch
  ) {
    long startNs = System.nanoTime();
    int nowInflight = IN_FLIGHT.incrementAndGet();

    String batchContent = PagePipeline.renderBatchContent(batch);

    String step1Prompt = ctx.prompts().step1().render(Map.of(
        "topic", ctx.topic(),
        "section", chapterTitle,
        "maxConcepts", String.valueOf(ctx.cfg().cards.maxConceptsPerPage),
        "content", batchContent
    ));

    return ctx.permits().acquireAnyAsync(ctx.permitPoolExecutor())
        .thenCompose(serverIndex -> {
          var llm = ctx.llms().get(serverIndex);

          log.info("START 3-STAGE BATCH pages=%d chapter='%s' inflight=%d server=%d url=%s%n",
              batch.size(), chapterTitle, nowInflight, serverIndex, llm.getUrl());

          // ── Step 1: extract concepts ──────────────────────────────────────
          return llm.generateAsync(step1Prompt)
              .thenComposeAsync(step1Result -> {
                String concepts = step1Result.response();
                logStep(1, chapterTitle, batch.size());
                appendDebugFile(ctx.outDir(), "step1_concepts", chapterTitle, concepts);

                String step2Prompt = ctx.prompts().step2().render(Map.of(
                    "topic", ctx.topic(),
                    "section", chapterTitle,
                    "concepts", concepts
                ));

                // ── Step 2: generate cards from concepts ──────────────────
                return llm.generateAsync(step2Prompt);
              }, ctx.cpuPoolExecutor())

              .thenComposeAsync(step2Result -> {
                String rawCards = step2Result.response();
                logStep(2, chapterTitle, batch.size());
                appendDebugFile(ctx.outDir(), "step2_cards", chapterTitle, rawCards);

                String step3Prompt = ctx.prompts().step3().render(Map.of(
                    "topic", ctx.topic(),
                    "cards", rawCards
                ));

                // ── Step 3: refine + deduplicate ──────────────────────────
                return llm.generateAsync(step3Prompt);
              }, ctx.cpuPoolExecutor())

              .thenApplyAsync(step3Result -> {
                try {
                  String refined = step3Result.response();
                  ctx.tracker().finishBatch(batch.size(), step3Result.metrics());
                  logStep(3, chapterTitle, batch.size());

                  return PagePipeline.parseCards(refined, batch, cardsParser);
                } finally {
                  ctx.permits().release(serverIndex);
                }
              }, ctx.cpuPoolExecutor())

              .whenComplete((res, ex) -> {
                long millis = (System.nanoTime() - startNs) / 1_000_000;
                log.info("END   3-STAGE BATCH pages=%d chapter='%s' took=%dms inflight=%d server=%d %s%n",
                    batch.size(), chapterTitle, millis, IN_FLIGHT.decrementAndGet(), serverIndex,
                    (ex != null ? "ERROR=" + ex : ""));
                if (ex == null) log.info(ctx.tracker().formatStatus(millis));
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
package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.CardsParser;
import nl.adgroot.pdfsummarizer.notes.DefaultCardsParser;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.notes.records.Card;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;

public class PagePipeline implements BatchPipeline {

  // Debug counter to verify parallelism (in-flight page pipelines)
  private static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);
  private static final AppLogger log = AppLogger.getLogger(PagePipeline.class);

  private final CardsParser cardsParser;

  /** Production default */
  public PagePipeline() {
    this(new DefaultCardsParser());
  }

  /** Injectable for tests / alternative parsers */
  public PagePipeline(CardsParser cardsParser) {
    this.cardsParser = cardsParser;
  }

  // -------------------------
  // Batching support
  // -------------------------

  // Matches:
  // ===PAGE 7===
  // ...content...
  // ===END PAGE===
  private static final Pattern PAGE_BLOCK =
      Pattern.compile("(?s)===PAGE\\s+(\\d+)===\\s*(.*?)\\s*===END PAGE===");

  static Map<Integer, String> splitPageBlocks(String md) {
    Map<Integer, String> out = new HashMap<>();
    if (md == null || md.isBlank()) return out;

    var m = PAGE_BLOCK.matcher(md);
    while (m.find()) {
      int idx = Integer.parseInt(m.group(1));
      String body = m.group(2).trim();
      out.put(idx, body);
    }
    return out;
  }

  static String renderBatchContent(List<PdfObject> batch) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < batch.size(); i++) {
      PdfObject p = batch.get(i);
      sb.append("===PAGE ").append(i + 1).append("===\n");

      String text = p.getTextReadFromPdf() == null ? "" : p.getTextReadFromPdf();
      sb.append(text);
      if (!text.endsWith("\n")) sb.append("\n");

      sb.append("===END PAGE===\n");
    }
    return sb.toString();
  }

  /**
   * Implements {@link BatchPipeline}: single-stage batch (all pages → one LLM call).
   * Uses {@code prompts.single()} as the prompt template.
   */
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
    String prompt = buildPrompt(prompts, topic, chapterTitle, cfg, batch);

    return permits.acquireAnyAsync(permitPoolExecutor).thenCompose(serverIndex -> {
      LlmClient llm = llms.get(serverIndex);
      log.info("START BATCH pages=%d chapter='%s' inflight=%d server=%d url=%s%n",
          batch.size(), chapterTitle, nowInflight, serverIndex, llm.getUrl());

      return llm.generateAsync(prompt)
          .thenApplyAsync(result -> {
            try {
              tracker.finishBatch(batch.size(), result.metrics());
              return parseCards(result.response(), batch, cardsParser);
            } finally {
              permits.release(serverIndex);
            }
          }, cpuPoolExecutor)
          .whenComplete((res, ex) -> {
            long millis = (System.nanoTime() - startNs) / 1_000_000;
            log.info("END   BATCH pages=%d chapter='%s' took=%dms inflight=%d server=%d %s%n",
                batch.size(), chapterTitle, millis, IN_FLIGHT.decrementAndGet(), serverIndex,
                (ex != null ? "ERROR=" + ex : ""));
            if (ex == null) log.info(tracker.formatStatus(millis));
          });
    });
  }

  private String buildPrompt(PromptTemplates prompts, String topic, String chapterTitle,
      AppConfig cfg, List<PdfObject> batch) {
    return prompts.single().render(Map.of(
        "topic", topic,
        "section", chapterTitle,
        "maxCards", String.valueOf(cfg.cards.maxCardsPerChunk),
        "content", renderBatchContent(batch)
    ));
  }

  static Map<Integer, List<String>> parseCards(String md, List<PdfObject> batch, CardsParser cardsParser) {
    Map<Integer, String> pageMd = splitPageBlocks(md);
    Map<Integer, List<String>> result = new HashMap<>();
    for (int i = 0; i < batch.size(); i++) {
      PdfObject p = batch.get(i);
      List<Card> cards = cardsParser.parse(pageMd.getOrDefault(i + 1, ""));
      result.put(p.getIndex(), cards.stream().map(Card::toString).toList());
    }
    return result;
  }
}
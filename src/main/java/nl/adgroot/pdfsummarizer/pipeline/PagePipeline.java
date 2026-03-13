package nl.adgroot.pdfsummarizer.pipeline;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import nl.adgroot.pdfsummarizer.AppLogger;
import nl.adgroot.pdfsummarizer.notes.CardsParser;
import nl.adgroot.pdfsummarizer.notes.DefaultCardsParser;
import nl.adgroot.pdfsummarizer.notes.records.Card;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;

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

  public static Map<Integer, String> splitPageBlocks(String md) {
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

  public static String renderBatchContent(List<PdfObject> batch) {
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
   * Uses {@code ctx.prompts().single()} as the prompt template.
   */
  @Override
  public CompletableFuture<Map<Integer, List<String>>> processBatchAsync(
      BatchContext ctx,
      String chapterTitle,
      List<PdfObject> batch
  ) {
    long startNs = System.nanoTime();
    int nowInflight = IN_FLIGHT.incrementAndGet();
    String prompt = buildPrompt(ctx, chapterTitle, batch);

    return ctx.permits().acquireAnyAsync(ctx.permitPoolExecutor()).thenCompose(serverIndex -> {
      var llm = ctx.llms().get(serverIndex);
      log.info("START BATCH pages=%d chapter='%s' inflight=%d server=%d url=%s%n",
          batch.size(), chapterTitle, nowInflight, serverIndex, llm.getUrl());

      return llm.generateAsync(prompt)
          .thenApplyAsync(result -> {
            try {
              ctx.tracker().finishBatch(batch.size(), result.metrics());
              return parseCards(result.response(), batch, cardsParser);
            } finally {
              ctx.permits().release(serverIndex);
            }
          }, ctx.cpuPoolExecutor())
          .whenComplete((res, ex) -> {
            long millis = (System.nanoTime() - startNs) / 1_000_000;
            log.info("END   BATCH pages=%d chapter='%s' took=%dms inflight=%d server=%d %s%n",
                batch.size(), chapterTitle, millis, IN_FLIGHT.decrementAndGet(), serverIndex,
                (ex != null ? "ERROR=" + ex : ""));
            if (ex == null) log.info(ctx.tracker().formatStatus(millis));
          });
    });
  }

  private String buildPrompt(BatchContext ctx, String chapterTitle, List<PdfObject> batch) {
    return ctx.prompts().single().render(Map.of(
        "topic", ctx.topic(),
        "section", chapterTitle,
        "maxCards", String.valueOf(ctx.cfg().cards.maxCardsPerChunk),
        "content", renderBatchContent(batch)
    ));
  }

  public static Map<Integer, List<String>> parseCards(String md, List<PdfObject> batch, CardsParser cardsParser) {
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
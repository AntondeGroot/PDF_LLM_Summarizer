package nl.adgroot.pdfsummarizer;

import java.util.ArrayList;
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
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.notes.CardsParser;
import nl.adgroot.pdfsummarizer.notes.DefaultCardsParser;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.notes.records.Card;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;

public class PagePipeline {

  // Debug counter to verify parallelism (in-flight page pipelines)
  private static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);

  public record PageResult(
      int index,
      int pageNr,
      List<String> cards,
      long millis,
      LlmMetrics metrics
  ) {}

  private final CardsParser cardsParser;

  /** Production default */
  public PagePipeline() {
    this(new DefaultCardsParser());
  }

  /** Injectable for tests / alternative parsers */
  public PagePipeline(CardsParser cardsParser) {
    this.cardsParser = cardsParser;
  }

  public CompletableFuture<PageResult> processPageAsync(
      List<LlmClient> llms,
      ServerPermitPool permits,
      ExecutorService permitPoolExecutor,
      ExecutorService cpuPoolExecutor,
      PromptTemplate promptTemplate,
      AppConfig cfg,
      String topic,
      String chapterTitle,
      int pageIndexInChapter,
      int pageNr,
      int nrPagesInChapter,
      String pageText,
      ProgressTracker tracker
  ) {
    long startNs = System.nanoTime();
    int nowInflight = IN_FLIGHT.incrementAndGet();

    String prompt = promptTemplate.render(Map.of(
        "topic", topic,
        "section", chapterTitle,
        "maxCards", String.valueOf(cfg.cards.maxCardsPerChunk),
        "content", pageText
    ));

    CompletableFuture<Integer> serverIndexFuture = permits.acquireAnyAsync(permitPoolExecutor);

    return serverIndexFuture.thenCompose(serverIndex -> {
      LlmClient llm = llms.get(serverIndex);

      synchronized (System.out) {
        System.out.printf(
            "START idx=%d/%d chapter='%s' inflight=%d server=%d url=%s%n",
            (pageIndexInChapter + 1), nrPagesInChapter, chapterTitle, nowInflight, serverIndex, llm.getUrl()
        );
      }

      return llm.generateAsync(prompt)
          .thenApplyAsync(result -> {
            try {
              // result
              String md = result.response();

              List<Card> cards = cardsParser.parse(md);
              List<String> cardStrings = new ArrayList<>();
              for (Card c : cards) cardStrings.add(c.toString());

              // metrics
              LlmMetrics metrics = result.metrics();
              long millis = (System.nanoTime() - startNs) / 1_000_000;
              tracker.finishPage(metrics);

              return new PageResult(pageIndexInChapter, pageNr, cardStrings, millis, metrics);
            } finally {
              permits.release(serverIndex);
            }
          }, cpuPoolExecutor)
          .whenComplete((res, ex) -> {
            int leftInflight = IN_FLIGHT.decrementAndGet();
            long millis = (System.nanoTime() - startNs) / 1_000_000;

            synchronized (System.out) {
              System.out.printf(
                  "END   idx=%d/%d chapter='%s' took=%dms inflight=%d server=%d %s%n",
                  (pageIndexInChapter + 1), nrPagesInChapter, chapterTitle, millis, leftInflight,
                  serverIndex,
                  (ex != null ? "ERROR=" + ex : "")
              );
            }
          });
    });
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
    for (PdfObject p : batch) {
      sb.append("===PAGE ").append(p.getIndex()).append("===\n");

      String text = p.getTextReadFromPdf() == null ? "" : p.getTextReadFromPdf();
      sb.append(text);
      if (!text.endsWith("\n")) sb.append("\n");

      sb.append("===END PAGE===\n");
    }
    return sb.toString();
  }

  /**
   * Batch pages into a single LLM request.
   *
   * IMPORTANT: This assumes your prompt.txt already contains the MULTIPLE PAGES + wrapper rules.
   */
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
    long startNs = System.nanoTime();
    int nowInflight = IN_FLIGHT.incrementAndGet();

    String batchContent = renderBatchContent(batch);

    String prompt = promptTemplate.render(Map.of(
        "topic", topic,
        "section", chapterTitle,
        "maxCards", String.valueOf(cfg.cards.maxCardsPerChunk),
        "content", batchContent
    ));

    CompletableFuture<Integer> serverIndexFuture = permits.acquireAnyAsync(permitPoolExecutor);

    return serverIndexFuture.thenCompose(serverIndex -> {
      LlmClient llm = llms.get(serverIndex);

      synchronized (System.out) {
        System.out.printf(
            "START BATCH pages=%d chapter='%s' inflight=%d server=%d url=%s%n",
            batch.size(), chapterTitle, nowInflight, serverIndex, llm.getUrl()
        );
      }

      return llm.generateAsync(prompt)
          .thenApplyAsync(result -> {
            try {
              String md = result.response();

              // 1) Split response into per-page markdown chunks
              Map<Integer, String> pageMd = splitPageBlocks(md);

              // 2) Parse cards per page using your existing CardsParser
              Map<Integer, List<String>> cardsByPageIndex = new HashMap<>();

              for (PdfObject p : batch) {
                int idx = p.getIndex();
                String perPage = pageMd.getOrDefault(idx, "");

                List<Card> cards = cardsParser.parse(perPage);
                List<String> cardStrings = new ArrayList<>(cards.size());
                for (Card c : cards) cardStrings.add(c.toString());

                cardsByPageIndex.put(idx, cardStrings);
              }

              // Tracking once per batch (you can refine this later)
              tracker.finishPage(result.metrics());

              return cardsByPageIndex;
            } finally {
              permits.release(serverIndex);
            }
          }, cpuPoolExecutor)
          .whenComplete((res, ex) -> {
            int leftInflight = IN_FLIGHT.decrementAndGet();
            long millis = (System.nanoTime() - startNs) / 1_000_000;

            synchronized (System.out) {
              System.out.printf(
                  "END   BATCH pages=%d chapter='%s' took=%dms inflight=%d server=%d %s%n",
                  batch.size(), chapterTitle, millis, leftInflight, serverIndex,
                  (ex != null ? "ERROR=" + ex : "")
              );
            }
          });
    });
  }
}
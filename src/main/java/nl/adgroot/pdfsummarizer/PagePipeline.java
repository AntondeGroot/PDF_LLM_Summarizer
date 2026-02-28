package nl.adgroot.pdfsummarizer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.records.Card;
import nl.adgroot.pdfsummarizer.notes.CardsParser;
import nl.adgroot.pdfsummarizer.notes.DefaultCardsParser;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Page;

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
    long startNs = System.nanoTime();
    int nowInflight = IN_FLIGHT.incrementAndGet();

    String prompt = promptTemplate.render(Map.of(
        "topic", topic,
        "topicTag", topic.toLowerCase().replace(" ", "-"),
        "section", chapterTitle,
        "chunkIndex", String.valueOf(pageIndexInChapter + 1),
        "chunkCount", String.valueOf(chunkCount),
        "created", LocalDate.now().toString(),
        "maxCards", String.valueOf(cfg.cards.maxCardsPerChunk),
        "content", page.toString()
    ));

    CompletableFuture<Integer> serverIndexFuture = permits.acquireAnyAsync(permitPoolExecutor);

    return serverIndexFuture.thenCompose(serverIndex -> {
      OllamaClient llm = llms.get(serverIndex);

      synchronized (System.out) {
        System.out.printf(
            "START idx=%d/%d pageNr=%d chapter='%s' inflight=%d server=%d url=%s%n",
            (pageIndexInChapter + 1), chunkCount, pageNr, chapterTitle, nowInflight,
            serverIndex, llm.getUrl()
        );
      }

      return llm.generateAsync(prompt)
          .thenApplyAsync(result -> {
            try {
              String md = result.response();
              LlmMetrics metrics = result.metrics();

              List<Card> cards = cardsParser.parse(md);

              List<String> cardStrings = new ArrayList<>(cards.size());
              for (Card c : cards) cardStrings.add(c.toString());

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
                  (pageIndexInChapter + 1), chunkCount, chapterTitle, millis, leftInflight,
                  serverIndex,
                  (ex != null ? "ERROR=" + ex : "")
              );
            }
          });
    });
  }
}
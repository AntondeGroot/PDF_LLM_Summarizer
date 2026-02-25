package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.config.ConfigLoader;
import nl.adgroot.pdfsummarizer.llm.LlmMetrics;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.OllamaClientsFactory;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.Card;
import nl.adgroot.pdfsummarizer.notes.CardParser;
import nl.adgroot.pdfsummarizer.notes.CardsPage;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.pdf.PdfPreviewComposer;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Chapter;
import nl.adgroot.pdfsummarizer.text.Page;
import org.apache.pdfbox.pdmodel.PDDocument;

public class Main {

  // Debug counter to verify parallelism (in-flight page pipelines)
  private static final AtomicInteger IN_FLIGHT = new AtomicInteger(0);

  public static void main(String[] args) throws Exception {

    Path pdfPath = Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("Learning Docker.pdf")).toURI()
    );

    // read config
    Path configPath = Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("config.json")).toURI()
    );
    AppConfig cfg = ConfigLoader.load(configPath);

    // init
    PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();
    PdfBoxPdfSplitter pdfSplitter = new PdfBoxPdfSplitter();
    String topic = filenameToTopic(pdfPath.getFileName().toString());
    NotesWriter writer = new NotesWriter();
    PdfPreviewComposer composer = new PdfPreviewComposer();
    List<OllamaClient> llms = OllamaClientsFactory.create(cfg.ollama);

    int servers = Math.max(1, cfg.ollama.servers);
    int perServerMax = Math.max(1, cfg.ollama.concurrency);
    ServerPermitPool permitPool = new ServerPermitPool(servers, perServerMax, true);

    PromptTemplate promptTemplate = PromptTemplate.load(Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt.txt")).toURI()));

    try(AppExecutors exec = AppExecutors.create(cfg)) {
      // Separate pool for waiting on permits (avoid blocking cpuPoolExecutor)
      ExecutorService permitPoolExecutor = exec.permitPoolExecutor();
      ExecutorService cpuPoolExecutor = exec.cpuPool(); // for parsing
      ExecutorService writerPool = exec.writerPool(); // Single writer thread: chapter files are written as soon as that chapter completes

      // read PDF
      List<String> pagesWithTOC = extractor.extractPages(pdfPath);
      List<PDDocument> pdfPages = pdfSplitter.splitInMemory(pdfPath);

      ParsedPDF parsedPdf = new ParsedPDF(pagesWithTOC, cfg.cards.nrOfLinesUsedForContext);
      if (cfg.preview.enabled) {
        int total = parsedPdf.getContent().size();
        int n = Math.min(cfg.preview.nrPages, total);

        List<Integer> selectedIndexes;

        if (cfg.preview.randomPages) {
          selectedIndexes = randomIndexes(total, n);
        } else {
          selectedIndexes = firstIndexes(n);
        }

        pdfPages = selectByIndex(pdfPages, selectedIndexes);
        parsedPdf.setContent(selectByIndex(parsedPdf.getContent(), selectedIndexes));

        Path out = Path.of("/Users/adgroot/Documents/preview-combined.pdf");
        composer.composeOriginalPlusTextPages(pdfPages, parsedPdf.getContent().stream().map(c->c.content).toList(), out);

        System.out.println("Preview mode: using pages " + selectedIndexes);
      }

      int totalPages = parsedPdf.getContent().size();
      ProgressTracker tracker = new ProgressTracker(totalPages);

      // For each chapter we create a "write when done" future.
      List<CompletableFuture<Void>> chapterWrites = new ArrayList<>();

      for (Chapter chapter : parsedPdf.getTableOfContent()) {
        final String chapterTitle = chapter.title;

        System.out.println("Scheduling chapter: " + chapterTitle);

        // Prepare chapter container up front
        CardsPage cardsPage = new CardsPage();
        cardsPage.addChapter(chapterTitle);
        cardsPage.addTopic(topic);

        // Pages for this chapter (keep original order from ParsedPDF)
        List<Page> pagesInChapter = parsedPdf.getContent()
            .stream()
            .filter(p -> p.chapter.equals(chapterTitle))
            .toList();

        List<CompletableFuture<PageResult>> pageFutures = new ArrayList<>(pagesInChapter.size());

        for (int i = 0; i < pagesInChapter.size(); i++) {
          int pageIndexInChapter = i; // stable for ordering
          Page page = pagesInChapter.get(i);

          CompletableFuture<PageResult> pf = processPageAsync(
              llms,
              permitPool,
              permitPoolExecutor,
              cpuPoolExecutor,
              promptTemplate,
              cfg,
              topic,
              chapterTitle,
              pageIndexInChapter,
              pagesInChapter.size(),
              page,
              tracker
          ).whenComplete((res, ex) -> {
            if (ex != null) {
              synchronized (System.out) {System.out.println("Page task failed in chapter '" + chapterTitle + "': " + ex);}
            } else {
              synchronized (System.out) {System.out.println(tracker.formatStatus(res.millis()));}
            }
          });

          pageFutures.add(pf);
        }

        // When this chapter's pages are all finished, write it (writerPool).
        CompletableFuture<Void> chapterWrite =
            CompletableFuture.allOf(pageFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> pageFutures.stream()
                    .map(CompletableFuture::join) // safe after allOf
                    .sorted(Comparator.comparingInt(PageResult::index))
                    .toList()
                )
                .thenAcceptAsync(results -> {
                  for (PageResult r : results) {
                    for (String card : r.cards()) {
                      cardsPage.addCard(card);
                    }
                  }

                  Path outDir = Path.of("/Users/adgroot/Documents");
                  try {
                    if(cardsPage.hasContent()){
                      writer.writeCard(outDir, cardsPage);
                      synchronized (System.out) {System.out.println("WROTE chapter: " + chapterTitle + " -> " + outDir.toAbsolutePath());}
                    }
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }, writerPool);

        chapterWrites.add(chapterWrite);
      }

      // Wait until ALL chapter writes are complete
      CompletableFuture.allOf(chapterWrites.toArray(new CompletableFuture[0])).join();

      System.out.println("Done. All chapters written.");

    }
  }

  /**
   * Full page pipeline:
   * - render prompt
   * - async HTTP call to Ollama (OkHttp enqueue inside OllamaClient)
   * - parse markdown on cpuPoolExecutor
   * - return PageResult (used for per-chapter ordering)
   */
  private static CompletableFuture<PageResult> processPageAsync(
      List<OllamaClient> llms,
      ServerPermitPool permits,
      ExecutorService permitPoolExecutor,
      ExecutorService cpuPoolExecutor,
      PromptTemplate promptTemplate,
      AppConfig cfg,
      String topic,
      String chapterTitle,
      int pageIndexInChapter,
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

    // Wait for whichever server is free (do NOT block cpuPoolExecutor)
    CompletableFuture<Integer> serverIndexFuture = permits.acquireAnyAsync(permitPoolExecutor);

    return serverIndexFuture.thenCompose(serverIndex -> {
      OllamaClient llm = llms.get(serverIndex);

      synchronized (System.out) {
        System.out.printf(
            "START idx=%d/%d chapter='%s' inflight=%d server=%d url=%s%n",
            (pageIndexInChapter + 1), chunkCount, chapterTitle, nowInflight,
            serverIndex, llm.getUrl()
        );
      }

      return llm.generateAsync(prompt)
          .thenApplyAsync(result -> {
            try {
              String md = result.response();
              LlmMetrics metrics = result.metrics();

              CardParser parser = new CardParser();
              List<Card> cards = parser.parse(md);

              List<String> cardStrings = new ArrayList<>(cards.size());
              for (Card c : cards) cardStrings.add(c.toString());

              long millis = (System.nanoTime() - startNs) / 1_000_000;

              tracker.finishPage(metrics);

              return new PageResult(pageIndexInChapter, cardStrings, millis, metrics);
            } finally {
              // IMPORTANT: release the server permit no matter what
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

  private static String filenameToTopic(String filename) {
    String noExt = filename.replaceAll("(?i)\\.pdf$", "");
    return noExt.replace('_', ' ').replace('-', ' ').trim();
  }

  private record PageResult(
      int index,
      List<String> cards,
      long millis,
      LlmMetrics metrics
  ) {}

  private static List<Integer> firstIndexes(int n) {
    List<Integer> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      list.add(i);
    }
    return list;
  }

  private static List<Integer> randomIndexes(int total, int n) {
    List<Integer> all = new ArrayList<>(total);
    for (int i = 0; i < total; i++) {
      all.add(i);
    }
    java.util.Collections.shuffle(all);
    return all.subList(0, n);
  }

  private static <T> List<T> selectByIndex(List<T> source, List<Integer> indexes) {
    return indexes.stream()
        .sorted() // keep original order for predictable flow
        .map(source::get)
        .toList();
  }
}
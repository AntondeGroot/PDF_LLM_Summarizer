package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import nl.adgroot.pdfsummarizer.PagePipeline.PageResult;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.config.ConfigLoader;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.OllamaClientsFactory;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
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
    PagePipeline pipeline = new PagePipeline();

    int servers = Math.max(1, cfg.ollama.servers);
    int perServerMax = Math.max(1, cfg.ollama.concurrency);
    ServerPermitPool permitPool = new ServerPermitPool(servers, perServerMax, true);

    PromptTemplate promptTemplate = PromptTemplate.load(Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt.txt")).toURI()));

    try (AppExecutors exec = AppExecutors.create(cfg)) {
      // Separate pool for waiting on permits (avoid blocking cpuPoolExecutor)
      ExecutorService permitPoolExecutor = exec.permitPoolExecutor();
      ExecutorService cpuPoolExecutor = exec.cpuPool(); // for parsing
      ExecutorService writerPool = exec.writerPool(); // Single writer thread: chapter files are written as soon as that chapter completes

      // read PDF
      List<String> pagesWithTOC = extractor.extractPages(pdfPath);
      List<PDDocument> pdfPages = pdfSplitter.splitInMemory(pdfPath);

      ParsedPDF parsedPdf = new ParsedPDF(pagesWithTOC, cfg.cards.nrOfLinesUsedForContext);

      // Keep only actual content pages aligned with parsedPdf.getContent()
      pdfPages = pdfPages.subList(
          pdfPages.size() - parsedPdf.getContent().size() - parsedPdf.getTableOfContent().getFirst().start,
          pdfPages.size()
      );
      pdfPages = pdfPages.subList(0, parsedPdf.getContent().size());

      if (cfg.preview.enabled) {
        int total = parsedPdf.getContent().size();
        int n = Math.min(cfg.preview.nrPages, total);

        List<Integer> selectedIndexes;

        if (cfg.preview.randomPages) {
          selectedIndexes = randomIndexes(total, n);
        } else {
          selectedIndexes = firstIndexes(n);
        }

        System.out.println("selected indexes for preview are: " + selectedIndexes);
        System.out.println("pdfpages size: " + pdfPages.size() + ", parsedPdf size: " + parsedPdf.getContent().size());

        pdfPages = selectByIndex(pdfPages, selectedIndexes);
        parsedPdf.setContent(selectByIndex(parsedPdf.getContent(), selectedIndexes));

        System.out.println("Preview mode: using pages " + selectedIndexes);
      }

      int totalPages = parsedPdf.getContent().size();
      ProgressTracker tracker = new ProgressTracker(totalPages);

      // Holds final “generated text page” per PDF page index (0..totalPages-1)
      Map<Integer, CardsPage> cardsPagesByIndex = new java.util.concurrent.ConcurrentHashMap<>();

      // For each chapter we create a "write when done" future.
      List<CompletableFuture<Void>> chapterWrites = new ArrayList<>();

      for (Chapter chapter : parsedPdf.getTableOfContent()) {
        final String chapterHeader = chapter.header;

        System.out.println("Scheduling chapter: " + chapterHeader);

        // Prepare chapter container up front
        CardsPage cardsPage = new CardsPage();
        cardsPage.addChapter(chapterHeader);
        cardsPage.addTopic(topic);

        // Pages for this chapter (keep original order from ParsedPDF)
        List<Page> pagesInChapter = parsedPdf.getContent()
            .stream()
            .filter(p -> p.chapter.equals(chapterHeader))
            .toList();

        List<CompletableFuture<PageResult>> pageFutures = new ArrayList<>(pagesInChapter.size());

        for (int i = 0; i < pagesInChapter.size(); i++) {
          int pageIndexInChapter = i; // stable for ordering
          Page page = pagesInChapter.get(i);
          int pageNr = page.pageNr;

          CompletableFuture<PageResult> pf = pipeline.processPageAsync(
              llms,
              permitPool,
              permitPoolExecutor,
              cpuPoolExecutor,
              promptTemplate,
              cfg,
              topic,
              chapterHeader,
              pageIndexInChapter,
              pageNr,
              pagesInChapter.size(),
              page,
              tracker
          ).whenComplete((res, ex) -> {
            if (ex != null) {
              synchronized (System.out) {
                System.out.println("Page task failed in chapter '" + chapterHeader + "': " + ex);
              }
            } else {
              System.out.println(res.cards());
              synchronized (System.out) {
                System.out.println(tracker.formatStatus(res.millis()));
              }
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
                    if (cardsPage.hasContent()) {
                      cardsPagesByIndex.put(results.getFirst().pageNr(), cardsPage);
                      writer.writeCard(outDir, cardsPage);
                      synchronized (System.out) {
                        System.out.println("WROTE chapter: " + chapterHeader + " -> " + outDir.toAbsolutePath());
                      }
                    }
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }, writerPool);

        chapterWrites.add(chapterWrite);
      }

      // Wait until ALL chapter writes are complete
      CompletableFuture.allOf(chapterWrites.toArray(new CompletableFuture[0])).join();

      // preview
      Path out = Path.of("/Users/adgroot/Documents/preview-combined.pdf");
      composer.composeOriginalPlusTextPages(pdfPages, cardsPagesByIndex, out);
      System.out.println("Done. All chapters written.");
    }
  }

  private static String filenameToTopic(String filename) {
    String noExt = filename.replaceAll("(?i)\\.pdf$", "");
    return noExt.replace('_', ' ').replace('-', ' ').trim();
  }

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
    return all.subList(0, n).stream().sorted().toList();
  }

  private static <T> List<T> selectByIndex(List<T> source, List<Integer> indexes) {
    return indexes.stream()
        .sorted() // keep original order for predictable flow
        .map(source::get)
        .toList();
  }
}
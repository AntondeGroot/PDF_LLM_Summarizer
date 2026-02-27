package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

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
    ChapterProcessor chapterProcessor = new ChapterProcessor();

    int servers = Math.max(1, cfg.ollama.servers);
    int perServerMax = Math.max(1, cfg.ollama.concurrency);
    ServerPermitPool permitPool = new ServerPermitPool(servers, perServerMax, true);

    PromptTemplate promptTemplate = PromptTemplate.load(Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt.txt")).toURI()
    ));

    try (AppExecutors exec = AppExecutors.create(cfg)) {
      ExecutorService permitPoolExecutor = exec.permitPoolExecutor();
      ExecutorService cpuPoolExecutor = exec.cpuPool();
      ExecutorService writerPool = exec.writerPool();

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

        List<Integer> selectedIndexes = cfg.preview.randomPages
            ? randomIndexes(total, n)
            : firstIndexes(n);

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

      Path outDir = Path.of("/Users/adgroot/Documents");

      for (Chapter chapter : parsedPdf.getTableOfContent()) {
        CompletableFuture<Void> writeFuture = chapterProcessor.processChapterAsync(
            chapter,
            parsedPdf,
            topic,
            pipeline,
            llms,
            permitPool,
            permitPoolExecutor,
            cpuPoolExecutor,
            writerPool,
            promptTemplate,
            cfg,
            tracker,
            writer,
            outDir,
            cardsPagesByIndex
        );

        chapterWrites.add(writeFuture);
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
        .sorted()
        .map(source::get)
        .toList();
  }
}
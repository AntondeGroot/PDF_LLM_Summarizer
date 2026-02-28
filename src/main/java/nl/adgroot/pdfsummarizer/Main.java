package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.config.ConfigLoader;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.OllamaClientsFactory;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.pdf.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.PdfPreparationService;
import nl.adgroot.pdfsummarizer.pdf.PdfPreviewComposer;
import nl.adgroot.pdfsummarizer.pdf.PreparedPdf;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class Main {

  public static void main(String[] args) throws Exception {

    Path pdfPath = Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("Learning Docker.pdf")).toURI()
    );

    Path configPath = Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("config.json")).toURI()
    );
    AppConfig cfg = ConfigLoader.load(configPath);

    // init
    PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();
    PdfBoxPdfSplitter pdfSplitter = new PdfBoxPdfSplitter();
    PdfPreparationService pdfPreparationService = new PdfPreparationService(extractor, pdfSplitter);

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

      // load + align + preview-select -> returns PdfObjects
      PreparedPdf prepared = pdfPreparationService.loadAndPrepare(pdfPath, cfg);
      var parsedPdf = prepared.parsed();
      List<PdfObject> pages = prepared.pdfPages();

      int totalPages = parsedPdf.getContent().size();
      ProgressTracker tracker = new ProgressTracker(totalPages);

      List<CompletableFuture<Void>> chapterWrites = new ArrayList<>();
      Path outDir = Path.of("/Users/adgroot/Documents");

      for (Chapter chapter : parsedPdf.getTableOfContent()) {
        CompletableFuture<Void> writeFuture = chapterProcessor.processChapterAsync(
            chapter,
            pages,
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
            outDir
        );

        chapterWrites.add(writeFuture);
      }

      CompletableFuture.allOf(chapterWrites.toArray(new CompletableFuture[0])).join();

      // preview output (only if enabled and combinePdfWithNotes)
      if (cfg.preview.enabled && cfg.preview.combinePdfWithNotes) {
        Path out = outDir.resolve("preview-combined.pdf");
        composer.composeOriginalPlusTextPages(pages, out);
      }

      System.out.println("Done. All chapters written.");
    }
  }

  private static String filenameToTopic(String filename) {
    String noExt = filename.replaceAll("(?i)\\.pdf$", "");
    return noExt.replace('_', ' ').replace('-', ' ').trim();
  }
}
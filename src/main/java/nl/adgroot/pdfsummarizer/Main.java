package nl.adgroot.pdfsummarizer;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.config.ConfigLoader;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreparationService;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreviewComposer;
import nl.adgroot.pdfsummarizer.pdf.parsing.PreparedPdf;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;

public class Main {

  private static final AppLogger log = AppLogger.getLogger(Main.class);
  private static Path pdfPath;
  private static Path outputPath;
  public static void main(String[] args) throws Exception {

   validateInputParameters(args);

    Path configPath = Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("config.json")).toURI()
    );

    AppConfig cfg = ConfigLoader.load(configPath);
    AppLogger.configure(cfg);

    boolean threeStage = cfg.ollama.pipeline3StepsMode;

    PromptTemplates prompts;
    BatchPipeline pipeline;

    if (threeStage) {
      PromptTemplate step1 = PromptTemplate.load(Paths.get(
          Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt_step1_concepts.txt")).toURI()
      ));
      PromptTemplate step2 = PromptTemplate.load(Paths.get(
          Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt_step2_cards.txt")).toURI()
      ));
      PromptTemplate step3 = PromptTemplate.load(Paths.get(
          Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt_step3_refine.txt")).toURI()
      ));
      prompts = new PromptTemplates(null, step1, step2, step3);
      pipeline = new ThreeStagePagePipeline();
      log.info("Pipeline: three-stage (concept extraction → card generation → refinement)");
    } else {
      PromptTemplate single = PromptTemplate.load(Paths.get(
          Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt.txt")).toURI()
      ));
      prompts = new PromptTemplates(single, null, null, null);
      pipeline = new PagePipeline();
      log.info("Pipeline: single-stage");
    }

    PreparedPdf prepared = new PdfPreparationService(
        new PdfBoxTextExtractor(), new PdfBoxPdfSplitter()
    ).loadAndPrepare(pdfPath, cfg);

    String topic = filenameToTopic(pdfPath.getFileName().toString());

    LlmFactory.LlmSetup llmSetup = LlmFactory.create(cfg);

    try (AppExecutors exec = AppExecutors.create(cfg)) {
      new AppRunner(
          new ChapterProcessor(),
          pipeline,
          new NotesWriter(),
          new PdfPreviewComposer()
      ).run(
          prepared, topic, cfg,
          llmSetup.llms(), llmSetup.permitPool(),
          exec, prompts, outputPath
      );
    }

    log.info("Done. All chapters written.");
  }

  static String filenameToTopic(String filename) {
    String noExt = filename.replaceAll("(?i)\\.pdf$", "");
    return noExt.replace('_', ' ').replace('-', ' ').trim();
  }

  static void validateInputParameters(String[] args){
    if (args.length < 2) {
      log.error("Usage: pdfsummarizer <path-to-pdf> <output-path>");
      System.exit(1);
    }

    try {
      pdfPath = Paths.get(args[0]).toAbsolutePath().normalize();
      outputPath = Paths.get(args[1]).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      log.error("One of the provided paths is invalid", e);
      System.exit(1);
      return;
    }

    if (!Files.exists(pdfPath) || !Files.isRegularFile(pdfPath)) {
      log.error("Argument 1 must point to an existing regular file");
      System.exit(1);
    }

    String fileName = pdfPath.getFileName().toString().toLowerCase(Locale.ROOT);
    if (!fileName.endsWith(".pdf")) {
      log.error("Argument 1 was not the path of a PDF file");
      System.exit(1);
    }

    if (Files.isSymbolicLink(pdfPath)) {
      log.error("Symbolic links are not allowed for the input PDF");
      System.exit(1);
    }

    Path parent = outputPath.getParent();
    if (parent != null && !Files.exists(parent)) {
      log.error("Output directory does not exist");
      System.exit(1);
    }
  }
}
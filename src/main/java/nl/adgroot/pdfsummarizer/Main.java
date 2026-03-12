package nl.adgroot.pdfsummarizer;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.config.ConfigLoader;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreparationService;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreviewComposer;
import nl.adgroot.pdfsummarizer.pdf.parsing.PreparedPdf;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxTextExtractor;

public class Main {

  private static final AppLogger log = AppLogger.getLogger(Main.class);

  record AppArgs(Path pdfPath, Path outputPath) {}

  public static void main(String[] args) throws Exception {
    AppArgs appArgs = validateInputParameters(args);

    AppConfig cfg = ConfigLoader.loadResource("config.json");
    AppLogger.configure(cfg);

    PipelineFactory.PipelineSetup pipelineSetup = PipelineFactory.create(cfg);
    LlmFactory.LlmSetup llmSetup = LlmFactory.create(cfg);

    PreparedPdf prepared = new PdfPreparationService(
        new PdfBoxTextExtractor(), new PdfBoxPdfSplitter()
    ).loadAndPrepare(appArgs.pdfPath(), cfg);

    String topic = PdfPreparationService.filenameToTopic(appArgs.pdfPath().getFileName().toString());

    try (AppExecutors exec = AppExecutors.create(cfg)) {
      new AppRunner(
          new ChapterProcessor(),
          pipelineSetup.pipeline(),
          new NotesWriter(),
          new PdfPreviewComposer()
      ).run(
          prepared, topic, cfg,
          llmSetup.llms(), llmSetup.permitPool(),
          exec, pipelineSetup.prompts(), appArgs.outputPath()
      );
    }

    log.info("Done. All chapters written.");
  }

  static AppArgs validateInputParameters(String[] args) {
    if (args.length < 2) {
      log.error("Usage: pdfsummarizer <path-to-pdf> <output-path>");
      System.exit(1);
    }

    Path pdfPath = null;
    Path outputPath = null;
    try {
      pdfPath = Paths.get(args[0]).toAbsolutePath().normalize();
      outputPath = Paths.get(args[1]).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      log.error("One of the provided paths is invalid", e);
      System.exit(1);
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

    return new AppArgs(pdfPath, outputPath);
  }
}
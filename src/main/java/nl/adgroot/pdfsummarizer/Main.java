package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.nio.file.Paths;
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

public class Main {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: pdfsummarizer <path-to-pdf>");
      System.exit(1);
    }
    Path pdfPath = Paths.get(args[0]);
    Path configPath = Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("config.json")).toURI()
    );

    AppConfig cfg = ConfigLoader.load(configPath);

    PromptTemplate promptTemplate = PromptTemplate.load(Paths.get(
        Objects.requireNonNull(Main.class.getClassLoader().getResource("prompt.txt")).toURI()
    ));

    PreparedPdf prepared = new PdfPreparationService(
        new PdfBoxTextExtractor(), new PdfBoxPdfSplitter()
    ).loadAndPrepare(pdfPath, cfg);

    String topic = filenameToTopic(pdfPath.getFileName().toString());

    LlmFactory.LlmSetup llmSetup = LlmFactory.create(cfg);

    try (AppExecutors exec = AppExecutors.create(cfg)) {
      new AppRunner(
          new ChapterProcessor(),
          new PagePipeline(),
          new NotesWriter(),
          new PdfPreviewComposer()
      ).run(
          prepared, topic, cfg,
          llmSetup.llms(), llmSetup.permitPool(),
          exec, promptTemplate,
          Path.of("/Users/adgroot/Documents")
      );
    }

    System.out.println("Done. All chapters written.");
  }

  static String filenameToTopic(String filename) {
    String noExt = filename.replaceAll("(?i)\\.pdf$", "");
    return noExt.replace('_', ' ').replace('-', ' ').trim();
  }
}
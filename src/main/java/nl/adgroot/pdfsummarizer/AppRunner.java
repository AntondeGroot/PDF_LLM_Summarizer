package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreviewComposer;
import nl.adgroot.pdfsummarizer.pdf.parsing.PreparedPdf;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class AppRunner {

  private final ChapterProcessor chapterProcessor;
  private final BatchPipeline pipeline;
  private final NotesWriter writer;
  private final PdfPreviewComposer composer;

  public AppRunner(
      ChapterProcessor chapterProcessor,
      BatchPipeline pipeline,
      NotesWriter writer,
      PdfPreviewComposer composer
  ) {
    this.chapterProcessor = chapterProcessor;
    this.pipeline = pipeline;
    this.writer = writer;
    this.composer = composer;
  }

  public void run(
      PreparedPdf prepared,
      String topic,
      AppConfig cfg,
      List<LlmClient> llms,
      ServerPermitPool permitPool,
      AppExecutors exec,
      PromptTemplates prompts,
      Path outDir
  ) throws Exception {
    List<PdfObject> pages = prepared.pdfPages();
    int totalPages = prepared.parsed().getContent().size();
    ProgressTracker tracker = new ProgressTracker(totalPages);

    ExecutorService permitPoolExecutor = exec.permitPoolExecutor();
    ExecutorService cpuPool = exec.cpuPool();
    ExecutorService writerPool = exec.writerPool();

    List<CompletableFuture<Void>> chapterWrites = new ArrayList<>();

    for (Chapter chapter : prepared.parsed().getTableOfContent()) {
      chapterWrites.add(chapterProcessor.processChapterAsync(
          chapter, pages, topic, pipeline,
          llms, permitPool,
          permitPoolExecutor, cpuPool, writerPool,
          prompts, cfg, tracker, writer, outDir
      ));
    }

    CompletableFuture.allOf(chapterWrites.toArray(new CompletableFuture[0])).join();

    if (cfg.preview.enabled && cfg.preview.combinePdfWithNotes) {
      composer.composeOriginalPlusTextPages(pages, outDir.resolve("preview-combined.pdf"));
    }
  }
}
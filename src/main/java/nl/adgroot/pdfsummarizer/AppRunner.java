package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.CheckpointManager;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfPreviewComposer;
import nl.adgroot.pdfsummarizer.pdf.parsing.PreparedPdf;
import nl.adgroot.pdfsummarizer.pipeline.BatchContext;
import nl.adgroot.pdfsummarizer.pipeline.BatchPipeline;
import nl.adgroot.pdfsummarizer.pipeline.ChapterProcessor;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;
import nl.adgroot.pdfsummarizer.pdf.parsing.Chapter;

public class AppRunner {

  private static final AppLogger log = AppLogger.getLogger(AppRunner.class);

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
    ProgressTracker tracker = new ProgressTracker(pages.size());
    CheckpointManager checkpoint = new CheckpointManager(outDir);

    BatchContext ctx = new BatchContext(
        llms, permitPool,
        exec.permitPoolExecutor(), exec.cpuPool(),
        prompts, cfg, topic, tracker, outDir
    );

    List<CompletableFuture<Void>> chapterWrites = new ArrayList<>();

    for (Chapter chapter : prepared.tableOfContent()) {
      if (checkpoint.isCompleted(chapter.header)) {
        log.info("Skipping chapter (already done): " + chapter.header);
        pages.stream()
            .filter(p -> chapter.header.equals(p.getChapter()))
            .forEach(p -> tracker.finishPage());
        continue;
      }
      chapterWrites.add(chapterProcessor.processChapterAsync(
          chapter, pages, pipeline, ctx, exec.writerPool(), writer, checkpoint
      ));
    }

    CompletableFuture.allOf(chapterWrites.toArray(new CompletableFuture[0])).join();

    if (cfg.preview.enabled && cfg.preview.combinePdfWithNotes) {
      composer.composeOriginalPlusTextPages(pages, outDir.resolve("preview-combined.pdf"));
    }
  }
}
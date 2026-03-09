package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;

public interface BatchPipeline {
  CompletableFuture<Map<Integer, List<String>>> processBatchAsync(
      List<LlmClient> llms,
      ServerPermitPool permits,
      ExecutorService permitPoolExecutor,
      ExecutorService cpuPoolExecutor,
      PromptTemplates prompts,
      AppConfig cfg,
      String topic,
      String chapterTitle,
      List<PdfObject> batch,
      ProgressTracker tracker,
      Path outDir
  );
}
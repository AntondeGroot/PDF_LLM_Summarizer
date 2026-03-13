package nl.adgroot.pdfsummarizer.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;

/**
 * Immutable context shared across all batches in a single processing run.
 * Groups the parameters that do not change per-batch so pipeline signatures stay narrow.
 */
public record BatchContext(
    List<LlmClient> llms,
    ServerPermitPool permits,
    ExecutorService permitPoolExecutor,
    ExecutorService cpuPoolExecutor,
    PromptTemplates prompts,
    AppConfig cfg,
    String topic,
    ProgressTracker tracker,
    Path outDir
) {}

package nl.adgroot.pdfsummarizer.pipeline;

import java.util.List;
import nl.adgroot.pdfsummarizer.AppLogger;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.ChatGptClient;
import nl.adgroot.pdfsummarizer.llm.ClaudeClient;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.OllamaClientsFactory;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;

public final class LlmFactory {

  public record LlmSetup(List<LlmClient> llms, ServerPermitPool permitPool) {}
  private static final AppLogger log = AppLogger.getLogger(LlmFactory.class);

  public static LlmSetup create(AppConfig cfg) {
    boolean ollamaEnabled = cfg.ollama.enabled;
    boolean claudeEnabled = cfg.claude.enabled;
    boolean openaiEnabled = cfg.openai.enabled;

    if (ollamaEnabled && (claudeEnabled || openaiEnabled)) {
      log.info("Multiple LLM backends enabled. Priority: Ollama > Claude > OpenAI. Ollama will be used.");
    } else if (claudeEnabled && openaiEnabled) {
      log.info("Both Claude and OpenAI are enabled; Claude will be used.");
    }

    if (ollamaEnabled) {
      List<LlmClient> llms = List.copyOf(OllamaClientsFactory.create(cfg.ollama));
      int servers = Math.max(1, cfg.ollama.servers);
      int perServerMax = Math.max(1, cfg.ollama.concurrency);
      return new LlmSetup(llms, new ServerPermitPool(servers, perServerMax, true));
    }

    if (claudeEnabled) {
      String apiKey = System.getenv("ANTHROPIC_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
        log.error("Anthropic API key was not set as an environment variable");
        throw new IllegalStateException("""
            ANTHROPIC_API_KEY environment variable not set.

            macOS/Linux:
                export ANTHROPIC_API_KEY="sk-ant-..."

            Windows PowerShell:
                setx ANTHROPIC_API_KEY "sk-ant-..."
            """);
      }
      List<LlmClient> llms = List.of(new ClaudeClient(cfg.claude, apiKey));
      int maxConcurrency = Math.max(1, cfg.claude.concurrency);
      return new LlmSetup(llms, new ServerPermitPool(1, maxConcurrency, true));
    }

    if (openaiEnabled) {
      String apiKey = System.getenv("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
        log.error("OpenAi API key was not set as an environment variable");
        throw new IllegalStateException("""
            OPENAI_API_KEY environment variable not set.

            macOS/Linux:
                export OPENAI_API_KEY="sk-..."

            Windows PowerShell:
                setx OPENAI_API_KEY "sk-..."
            """);
      }
      List<LlmClient> llms = List.of(new ChatGptClient(cfg.openai, apiKey));
      int maxConcurrency = Math.max(1, cfg.openai.concurrency);
      return new LlmSetup(llms, new ServerPermitPool(1, maxConcurrency, true));
    }

    log.error("No LLM backend enabled. Enable ollama, claude, or openai in config.json.");
    throw new IllegalStateException(
        "No LLM backend enabled. Enable ollama, claude, or openai in config.json.");
  }
}
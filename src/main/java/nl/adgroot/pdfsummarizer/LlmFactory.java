package nl.adgroot.pdfsummarizer;

import java.util.List;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.ChatGptClient;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.OllamaClientsFactory;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;

public final class LlmFactory {

  public record LlmSetup(List<LlmClient> llms, ServerPermitPool permitPool) {}

  public static LlmSetup create(AppConfig cfg) {
    boolean openaiEnabled = cfg.openai.enabled;
    boolean ollamaEnabled = cfg.ollama.enabled;

    if (openaiEnabled && ollamaEnabled) {
      System.out.println("Both Ollama and OpenAI are enabled; Ollama will be used.");
    }

    if (ollamaEnabled) {
      List<LlmClient> llms = List.copyOf(OllamaClientsFactory.create(cfg.ollama));
      int servers = Math.max(1, cfg.ollama.servers);
      int perServerMax = Math.max(1, cfg.ollama.concurrency);
      return new LlmSetup(llms, new ServerPermitPool(servers, perServerMax, true));
    }

    if (openaiEnabled) {
      String apiKey = System.getenv("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
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

    throw new IllegalStateException(
        "No LLM backend enabled. Enable either cfg.openai.enabled or cfg.ollama.enabled.");
  }
}
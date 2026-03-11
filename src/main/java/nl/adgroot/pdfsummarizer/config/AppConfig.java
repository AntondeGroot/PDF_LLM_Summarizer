package nl.adgroot.pdfsummarizer.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
  public OllamaConfig ollama = new OllamaConfig();
  public OpenAiConfig openai = new OpenAiConfig();
  public ChunkingConfig chunking = new ChunkingConfig();
  public CardsConfig cards = new CardsConfig();
  public PreviewConfig preview = new PreviewConfig();
  public OutputConfig output = new OutputConfig();
  public LoggingConfig logging = new LoggingConfig();

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class LoggingConfig {
    /** One of: DEBUG, INFO, WARN, ERROR */
    public String level = "INFO";
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OllamaConfig {
    // NEW: number of servers (e.g. 3 -> ports 11434..11436)
    public boolean enabled = true;
    public int servers = 1;

    // NEW: models to use (per server). If you only provide 1, it will be used for all servers.
    public String[] modelsPerServer = { "llama3.1:8b" };

    public double temperature = 0.3;
    public int timeoutSeconds = 120;

    // interpreted as per-server concurrency in your Main (permits per server + OkHttp dispatcher)
    public int concurrency = 2;

    // Optional: allow overriding host/basePort/api path without changing code
    public String host = "127.0.0.1";
    public int basePort = 11434;
    public String generatePath = "/api/generate";

    // When true, multiple pages are batched into a single LLM request.
    // When false, each page is sent as a separate request.
    public boolean localBatching = true;

    // "single"     false → uses prompt.txt (one LLM call per batch).
    // "three-stage" true → uses prompt_step1_concepts.txt → prompt_step2_cards.txt → prompt_step3_refine.txt.
    public boolean pipeline3StepsMode = false;
  }
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OpenAiConfig {
    public boolean enabled = false;
    public String baseUrl = "https://api.openai.com";
    public String responsesPath = "/v1/responses";
    public String model = "gpt-4.1-mini";
    public int timeoutSeconds = 120;
    public int concurrency = 4;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChunkingConfig {
    public int maxTokensPerChunk = 12000;
    public int minTokensPerChunk = 2000;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CardsConfig {
    public int maxCardsPerChunk = 12;
    public int nrOfLinesUsedForContext = 0;
    // Used by the three-stage pipeline (Step 1): max concepts extracted per page.
    public int maxConceptsPerPage = 10;
  }
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class PreviewConfig {
    public boolean enabled = false;
    public boolean randomPages = true;
    public int nrPages = 5;
    public boolean combinePdfWithNotes = true;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OutputConfig {
    public int maxFilenameLength = 120;
  }
}
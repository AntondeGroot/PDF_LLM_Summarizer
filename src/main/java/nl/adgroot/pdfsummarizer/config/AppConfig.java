package nl.adgroot.pdfsummarizer.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

  public OllamaConfig ollama = new OllamaConfig();
  public ChunkingConfig chunking = new ChunkingConfig();
  public CardsConfig cards = new CardsConfig();
  public OutputConfig output = new OutputConfig();

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OllamaConfig {
    // NEW: number of servers (e.g. 3 -> ports 11434..11436)
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
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChunkingConfig {
    public int maxCharsPerChunk = 9000;
    public int minCharsPerChunk = 2000;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class CardsConfig {
    public int maxCardsPerChunk = 12;
    public int nrOfLinesUsedForContext = 0;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OutputConfig {
    public int maxFilenameLength = 120;
  }
}
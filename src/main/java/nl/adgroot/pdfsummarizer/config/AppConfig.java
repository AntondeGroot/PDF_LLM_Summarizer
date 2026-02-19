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
    public String url = "http://localhost:11434/api/generate";
    public String model = "llama3.1:8b";
    public double temperature = 0.3;
    public int timeoutSeconds = 120;
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
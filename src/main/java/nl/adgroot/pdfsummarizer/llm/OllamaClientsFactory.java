package nl.adgroot.pdfsummarizer.llm;

import java.util.ArrayList;
import java.util.List;

import nl.adgroot.pdfsummarizer.config.AppConfig;

public final class OllamaClientsFactory {

  private OllamaClientsFactory() {
    // utility class
  }

  /**
   * Creates one OllamaClient per configured server.
   *
   * Rules:
   * - host defaults to 127.0.0.1
   * - basePort defaults to 11434
   * - generatePath defaults to /api/generate
   * - if only 1 model is configured, reuse it for all servers
   * - if multiple models are configured, map by server index (wrap around)
   */
  public static List<OllamaClient> create(AppConfig.OllamaConfig cfg) {

    List<OllamaClient> clients = new ArrayList<>();
    int servers = Math.max(1, cfg.servers);
    int basePort = cfg.basePort;
    String path = cfg.generatePath;
    String[] models = cfg.modelsPerServer;

    for (int i = 0; i < servers; i++) {
      int port = basePort + i;
      String url = "http://" + cfg.host + ":" + port + path;
      String model = models[i % models.length];

      clients.add(new OllamaClient(cfg, url, model));
    }

    return clients;
  }
}
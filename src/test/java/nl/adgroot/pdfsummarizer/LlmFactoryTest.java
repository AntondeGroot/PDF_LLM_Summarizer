package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.ChatGptClient;
import nl.adgroot.pdfsummarizer.pipeline.LlmFactory;
import org.junit.jupiter.api.Test;

class LlmFactoryTest {

  // ── Ollama ───────────────────────────────────────────────────────────────

  @Test
  void create_ollamaEnabled_returnsOllamaClients() {
    AppConfig cfg = new AppConfig();
    cfg.ollama.enabled = true;
    cfg.openai.enabled = false;
    cfg.ollama.servers = 1;

    LlmFactory.LlmSetup setup = LlmFactory.create(cfg);

    assertFalse(setup.llms().isEmpty());
    assertEquals(OllamaClient.class, setup.llms().getFirst().getClass());
  }

  @Test
  void create_ollamaEnabled_respectsServerCount() {
    AppConfig cfg = new AppConfig();
    cfg.ollama.enabled = true;
    cfg.openai.enabled = false;
    cfg.ollama.servers = 3;

    LlmFactory.LlmSetup setup = LlmFactory.create(cfg);

    assertEquals(3, setup.llms().size());
  }

  @Test
  void create_bothEnabled_ollamaWins() {
    AppConfig cfg = new AppConfig();
    cfg.ollama.enabled = true;
    cfg.openai.enabled = true;
    cfg.ollama.servers = 1;

    LlmFactory.LlmSetup setup = LlmFactory.create(cfg);

    assertEquals(OllamaClient.class, setup.llms().getFirst().getClass(),
        "Ollama should take priority when both backends are enabled");
  }

  // ── OpenAI ───────────────────────────────────────────────────────────────

  @Test
  void create_openAiEnabled_missingApiKey_throws() {
    assumeTrue(System.getenv("OPENAI_API_KEY") == null,
        "Skipped: OPENAI_API_KEY is set in this environment");

    AppConfig cfg = new AppConfig();
    cfg.ollama.enabled = false;
    cfg.openai.enabled = true;

    assertThrows(IllegalStateException.class, () -> LlmFactory.create(cfg));
  }

  @Test
  void create_openAiEnabled_withApiKey_returnsChatGptClient() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null,
        "Skipped: OPENAI_API_KEY is not set in this environment");

    AppConfig cfg = new AppConfig();
    cfg.ollama.enabled = false;
    cfg.openai.enabled = true;

    LlmFactory.LlmSetup setup = LlmFactory.create(cfg);

    assertEquals(1, setup.llms().size());
    assertEquals(ChatGptClient.class, setup.llms().getFirst().getClass());
  }

  // ── Neither enabled ──────────────────────────────────────────────────────

  @Test
  void create_neitherEnabled_throws() {
    AppConfig cfg = new AppConfig();
    cfg.ollama.enabled = false;
    cfg.openai.enabled = false;

    assertThrows(IllegalStateException.class, () -> LlmFactory.create(cfg));
  }
}
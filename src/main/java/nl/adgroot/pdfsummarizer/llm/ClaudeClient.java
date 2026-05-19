package nl.adgroot.pdfsummarizer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import nl.adgroot.pdfsummarizer.llm.records.LlmResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ClaudeClient implements LlmClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final MediaType JSON = MediaType.parse("application/json");
  private static final String ANTHROPIC_VERSION = "2023-06-01";

  private final OkHttpClient http;
  private final RetryPolicy retryPolicy;
  private final String url;
  private final String apiKey;
  private final String model;
  private final int maxTokens;

  public ClaudeClient(AppConfig.ClaudeConfig cfg, String apiKey) {
    this.url = cfg.baseUrl + cfg.messagesPath;
    this.apiKey = apiKey;
    this.model = cfg.model;
    this.maxTokens = cfg.maxTokens;
    this.http = HttpClientFactory.create(Duration.ofSeconds(cfg.timeoutSeconds), cfg.concurrency);
    this.retryPolicy = RetryPolicy.defaults();
  }

  @Override
  public CompletableFuture<LlmResult> generateAsync(String prompt) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model);
    body.put("max_tokens", maxTokens);
    body.putArray("messages")
        .addObject()
        .put("role", "user")
        .put("content", prompt);

    Request req = new Request.Builder()
        .url(url)
        .addHeader("x-api-key", apiKey)
        .addHeader("anthropic-version", ANTHROPIC_VERSION)
        .addHeader("content-type", "application/json")
        .post(RequestBody.create(body.toString(), JSON))
        .build();

    CompletableFuture<LlmResult> future = new CompletableFuture<>();
    retryPolicy.enqueue(http, req, future, this::parseResponse);
    return future;
  }

  private LlmResult parseResponse(Response r) throws IOException {
    if (!r.isSuccessful()) {
      String body = r.body() != null ? r.body().string() : "";
      throw new IOException("Claude error " + r.code() + ":\n" + body);
    }

    String raw = Objects.requireNonNull(r.body()).string();
    JsonNode json = MAPPER.readTree(raw);

    String text = "";
    for (JsonNode block : json.path("content")) {
      if ("text".equals(block.path("type").asText())) {
        text = block.path("text").asText("");
        break;
      }
    }

    JsonNode usage = json.path("usage");
    return new LlmResult(text, new LlmMetrics(
        0, 0, 0,
        usage.path("input_tokens").asInt(),
        usage.path("output_tokens").asInt()
    ));
  }

  @Override
  public String getName() {
    return "claude/" + model;
  }

  @Override
  public String getUrl() {
    return url;
  }
}
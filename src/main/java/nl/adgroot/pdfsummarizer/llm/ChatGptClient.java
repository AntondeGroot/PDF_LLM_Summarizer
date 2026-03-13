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

public class ChatGptClient implements LlmClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final MediaType JSON = MediaType.parse("application/json");

  private final OkHttpClient http;
  private final RetryPolicy retryPolicy;
  private final String url;
  private final String apiKey;
  private final String model;

  public ChatGptClient(AppConfig.OpenAiConfig cfg, String apiKey) {
    this.url = cfg.baseUrl + cfg.responsesPath;
    this.apiKey = apiKey;
    this.model = cfg.model;
    this.http = HttpClientFactory.create(Duration.ofSeconds(cfg.timeoutSeconds), cfg.concurrency);
    this.retryPolicy = RetryPolicy.defaults();
  }

  @Override
  public CompletableFuture<LlmResult> generateAsync(String prompt) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("model", model);
    body.putArray("input")
        .addObject()
        .put("role", "user")
        .put("content", prompt);

    Request req = new Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer " + apiKey)
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create(body.toString(), JSON))
        .build();

    CompletableFuture<LlmResult> future = new CompletableFuture<>();
    retryPolicy.enqueue(http, req, future, this::parseResponse);
    return future;
  }

  private LlmResult parseResponse(Response r) throws IOException {
    if (!r.isSuccessful()) {
      String body = r.body() != null ? r.body().string() : "";
      throw new IOException("OpenAI error " + r.code() + ":\n" + body);
    }

    String raw = Objects.requireNonNull(r.body()).string();
    JsonNode json = MAPPER.readTree(raw);

    String text = json.path("output_text").asText("");
    if (text.isBlank()) {
      text = extractTextFallback(json);
    }

    return new LlmResult(text, new LlmMetrics(0, 0, 0, 0, 0));
  }

  private static String extractTextFallback(JsonNode json) {
    for (JsonNode out : json.path("output")) {
      for (JsonNode c : out.path("content")) {
        if ("output_text".equals(c.path("type").asText())) {
          return c.path("text").asText("");
        }
      }
    }
    return "";
  }

  @Override
  public String getName() {
    return "chatgpt/" + model;
  }

  @Override
  public String getUrl() {
    return url;
  }
}

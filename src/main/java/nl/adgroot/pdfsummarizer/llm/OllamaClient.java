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
import okhttp3.ResponseBody;

public class OllamaClient implements LlmClient {

  private static final MediaType JSON = MediaType.parse("application/json");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final OkHttpClient http;
  private final RetryPolicy retryPolicy;
  private final String url;
  private final String model;
  private final double temperature;

  public OllamaClient(AppConfig.OllamaConfig cfg, String url, String model) {
    this.url = url;
    this.model = model;
    this.temperature = cfg.temperature;
    this.http = HttpClientFactory.create(Duration.ofSeconds(cfg.timeoutSeconds), cfg.concurrency);
    this.retryPolicy = RetryPolicy.defaults();
  }

  @Override
  public CompletableFuture<LlmResult> generateAsync(String prompt) {
    ObjectNode req = MAPPER.createObjectNode();
    req.put("model", model);
    req.put("prompt", prompt);
    req.put("stream", false);
    req.put("temperature", temperature);

    Request request = new Request.Builder()
        .url(url)
        .post(RequestBody.create(req.toString(), JSON))
        .build();

    CompletableFuture<LlmResult> future = new CompletableFuture<>();
    retryPolicy.enqueue(http, request, future, this::parseResponse);
    return future;
  }

  private LlmResult parseResponse(Response r) throws IOException {
    if (!r.isSuccessful()) {
      String body = readBodySafely(r.body());
      throw new IOException("Ollama error: " + r.code() + " " + r.message() + "\n" + body);
    }

    String body = Objects.requireNonNull(r.body()).string();
    JsonNode json = MAPPER.readTree(body);

    String response = json.path("response").asText("");
    LlmMetrics metrics = new LlmMetrics(
        json.path("total_duration").asLong(),
        json.path("prompt_eval_duration").asLong(),
        json.path("eval_duration").asLong(),
        json.path("prompt_eval_count").asInt(),
        json.path("eval_count").asInt()
    );

    return new LlmResult(response, metrics);
  }

  private static String readBodySafely(ResponseBody body) {
    if (body == null) return "";
    try {
      return body.string();
    } catch (IOException ignored) {
      return "";
    }
  }

  @Override
  public String getName() {
    return "Ollama";
  }

  @Override
  public String getUrl() {
    return url;
  }

  public String getModel() {
    return model;
  }
}

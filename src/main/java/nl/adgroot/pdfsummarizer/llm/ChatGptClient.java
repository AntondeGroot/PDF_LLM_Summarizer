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
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatGptClient implements LlmClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final MediaType JSON = MediaType.parse("application/json");

  private final OkHttpClient http;
  private final String url;
  private final String apiKey;
  private final String model;

  public ChatGptClient(AppConfig.OpenAiConfig cfg, String apiKey) {
    this.url = cfg.baseUrl + cfg.responsesPath;
    this.apiKey = apiKey;
    this.model = cfg.model;

    this.http = HttpClientFactory.create(
        Duration.ofSeconds(cfg.timeoutSeconds),
        cfg.concurrency
    );
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

    executeWithRetry(req, future, 0);

    return future;
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

  private static final int MAX_RETRIES = 5;
  private static final long BASE_DELAY_MS = 500;

  private void executeWithRetry(Request request,
      CompletableFuture<LlmResult> future,
      int attempt) {

    http.newCall(request).enqueue(new Callback() {

      @Override
      public void onFailure(Call call, IOException e) {
        retryOrFail(request, future, attempt, e);
      }

      @Override
      public void onResponse(Call call, Response resp) {
        try (Response r = resp) {

          int code = r.code();

          if (code == 429 || (code >= 500 && code < 600)) {
            retryOrFail(request, future, attempt,
                new IOException("Retryable HTTP error: " + code));
            return;
          }

          if (!r.isSuccessful()) {
            String body = r.body() != null ? r.body().string() : "";
            future.completeExceptionally(
                new IOException("OpenAI error " + code + ":\n" + body));
            return;
          }

          String raw = Objects.requireNonNull(r.body()).string();
          JsonNode json = MAPPER.readTree(raw);

          String text = json.path("output_text").asText("");
          if (text.isBlank()) {
            text = extractTextFallback(json);
          }

          LlmMetrics metrics = new LlmMetrics(0,0,0,0,0);

          future.complete(new LlmResult(text, metrics));

        } catch (Exception e) {
          future.completeExceptionally(e);
        }
      }
    });
  }

  private void retryOrFail(Request request,
      CompletableFuture<LlmResult> future,
      int attempt,
      Exception error) {

    if (attempt >= MAX_RETRIES) {
      future.completeExceptionally(error);
      return;
    }

    long delay = (long) (BASE_DELAY_MS * Math.pow(2, attempt));

    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    executeWithRetry(request, future, attempt + 1);
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
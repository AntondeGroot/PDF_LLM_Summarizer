package nl.adgroot.pdfsummarizer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OllamaClient {

  private static final MediaType JSON = MediaType.parse("application/json");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final OkHttpClient http;
  private final String url;
  private final String model;
  private final double temperature;

  public OllamaClient(AppConfig.OllamaConfig cfg, String url, String model) {
    this.url = url;
    this.model = model;
    this.temperature = cfg.temperature;

    Duration t = Duration.ofSeconds(cfg.timeoutSeconds);

    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequests(cfg.concurrency);
    dispatcher.setMaxRequestsPerHost(cfg.concurrency);

    this.http = new OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(t)
        .readTimeout(t)
        .writeTimeout(t)
        .callTimeout(t)
        .build();
  }

  /** Async / parallel-friendly API: uses OkHttp enqueue(). */
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

    http.newCall(request).enqueue(new Callback() {
      @Override
      public void onFailure(Call call, IOException e) {
        future.completeExceptionally(e);
      }

      @Override
      public void onResponse(Call call, Response resp) {
        try (Response r = resp) {
          if (!r.isSuccessful()) {
            String body = readBodySafely(r.body());
            future.completeExceptionally(
                new IOException("Ollama error: " + r.code() + " " + r.message() + "\n" + body)
            );
            return;
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

          future.complete(new LlmResult(response, metrics));
        } catch (Exception e) {
          future.completeExceptionally(e);
        }
      }
    });

    return future;
  }

  private static String readBodySafely(ResponseBody body) {
    if (body == null) return "";
    try {
      return body.string();
    } catch (IOException ignored) {
      return "";
    }
  }

  public String getUrl() {
    return url;
  }

  public String getModel() {
    return model;
  }
}
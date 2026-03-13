package nl.adgroot.pdfsummarizer.llm;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import nl.adgroot.pdfsummarizer.llm.records.LlmResult;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Wraps OkHttp's async enqueue with exponential-backoff retries.
 *
 * <p>Network failures and retryable HTTP status codes (429, 5xx) are retried up to
 * {@code maxRetries} times. All other responses (including non-2xx error codes) are
 * forwarded to the {@link ResponseProcessor} so each client can format its own error
 * message.
 */
public class RetryPolicy {

  private static final int DEFAULT_MAX_RETRIES = 5;
  private static final long DEFAULT_BASE_DELAY_MS = 500;

  private final int maxRetries;
  private final long baseDelayMs;

  public RetryPolicy(int maxRetries, long baseDelayMs) {
    this.maxRetries = maxRetries;
    this.baseDelayMs = baseDelayMs;
  }

  public static RetryPolicy defaults() {
    return new RetryPolicy(DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MS);
  }

  /**
   * Parses a successful (non-retryable) HTTP response into an {@link LlmResult}.
   * Throw any exception to fail the future.
   */
  @FunctionalInterface
  public interface ResponseProcessor {
    LlmResult process(Response response) throws Exception;
  }

  /** Enqueue the request, retrying automatically on network errors and retryable HTTP codes. */
  public void enqueue(OkHttpClient http, Request request,
      CompletableFuture<LlmResult> future,
      ResponseProcessor processor) {
    enqueue(http, request, future, processor, 0);
  }

  private void enqueue(OkHttpClient http, Request request,
      CompletableFuture<LlmResult> future,
      ResponseProcessor processor,
      int attempt) {

    http.newCall(request).enqueue(new Callback() {

      @Override
      public void onFailure(Call call, IOException e) {
        retryOrFail(http, request, future, processor, attempt, e);
      }

      @Override
      public void onResponse(Call call, Response resp) {
        try (Response r = resp) {
          int code = r.code();
          if (isRetryable(code)) {
            retryOrFail(http, request, future, processor, attempt,
                new IOException("Retryable HTTP " + code));
            return;
          }
          future.complete(processor.process(r));
        } catch (Exception e) {
          future.completeExceptionally(e);
        }
      }
    });
  }

  private void retryOrFail(OkHttpClient http, Request request,
      CompletableFuture<LlmResult> future,
      ResponseProcessor processor,
      int attempt, Exception error) {

    if (attempt >= maxRetries) {
      future.completeExceptionally(error);
      return;
    }

    long delay = (long) (baseDelayMs * Math.pow(2, attempt));
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    enqueue(http, request, future, processor, attempt + 1);
  }

  private static boolean isRetryable(int code) {
    return code == 429 || (code >= 500 && code < 600);
  }
}

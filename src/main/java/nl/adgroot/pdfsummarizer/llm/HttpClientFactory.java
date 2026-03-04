package nl.adgroot.pdfsummarizer.llm;

import java.time.Duration;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

public final class HttpClientFactory {

  private HttpClientFactory() {}

  public static OkHttpClient create(Duration timeout, int concurrency) {
    Dispatcher dispatcher = new Dispatcher();
    dispatcher.setMaxRequests(concurrency);
    dispatcher.setMaxRequestsPerHost(concurrency);

    return new OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(new ConnectionPool())
        .retryOnConnectionFailure(true)
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .writeTimeout(timeout)
        .callTimeout(timeout)
        .build();
  }
}
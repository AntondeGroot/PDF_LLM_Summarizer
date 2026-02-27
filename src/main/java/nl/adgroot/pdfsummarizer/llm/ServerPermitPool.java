package nl.adgroot.pdfsummarizer.llm;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

public class ServerPermitPool {

  private final Semaphore[] permits;

  /**
   * @param servers number of servers (>= 1)
   * @param permitsPerServer max concurrent calls per server (>= 1)
   * @param fair whether semaphores should be fair: FIFO
   */
  public ServerPermitPool(int servers, int permitsPerServer, boolean fair) {
    int s = Math.max(1, servers);
    int p = Math.max(1, permitsPerServer);

    this.permits = new Semaphore[s];
    for (int i = 0; i < s; i++) {
      this.permits[i] = new Semaphore(p, fair);
    }
  }

  public int servers() {
    return permits.length;
  }

  /**
   * Blocks the calling thread until any server permit is available.
   * Returns the server index that was acquired.
   */
  public int acquireAny() {
    for (;;) {
      for (int i = 0; i < permits.length; i++) {
        if (permits[i].tryAcquire()) {
          return i;
        }
      }
      try {
        Thread.sleep(2); // small backoff
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for a server permit", e);
      }
    }
  }

  /**
   * Acquires any server permit asynchronously using the provided executor.
   */
  public CompletableFuture<Integer> acquireAnyAsync(Executor executor) {
    Objects.requireNonNull(executor, "executor");
    return CompletableFuture.supplyAsync(this::acquireAny, executor);
  }

  public void release(int serverIndex) {
    if (serverIndex < 0 || serverIndex >= permits.length) {
      throw new IllegalArgumentException("Invalid server index: " + serverIndex);
    }
    permits[serverIndex].release();
  }
}

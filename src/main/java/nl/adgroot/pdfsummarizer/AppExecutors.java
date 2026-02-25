package nl.adgroot.pdfsummarizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import nl.adgroot.pdfsummarizer.config.AppConfig;

public final class AppExecutors implements AutoCloseable {

  private final ExecutorService permitPoolExecutor;
  private final ExecutorService cpuPool;
  private final ExecutorService writerPool;

  private AppExecutors(ExecutorService permitPoolExecutor, ExecutorService cpuPool, ExecutorService writerPool) {
    this.permitPoolExecutor = permitPoolExecutor;
    this.cpuPool = cpuPool;
    this.writerPool = writerPool;
  }

  public static AppExecutors create(AppConfig cfg) {
    ExecutorService permitPoolExecutor = Executors.newCachedThreadPool(r -> {
      Thread t = new Thread(r, "llm-permit");
      t.setDaemon(false);
      return t;
    });

    int cpuThreads = Math.max(1, cfg.ollama.concurrency);

    ThreadFactory cpuTf = new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger(1);

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "cpu-worker-" + n.getAndIncrement());
        t.setDaemon(false);
        return t;
      }
    };

    ExecutorService cpuPool = Executors.newFixedThreadPool(cpuThreads, cpuTf);

    ExecutorService writerPool = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "writer");
      t.setDaemon(false);
      return t;
    });

    return new AppExecutors(permitPoolExecutor, cpuPool, writerPool);
  }

  public ExecutorService permitPoolExecutor() {
    return permitPoolExecutor;
  }

  public ExecutorService cpuPool() {
    return cpuPool;
  }

  public ExecutorService writerPool() {
    return writerPool;
  }

  @Override
  public void close() throws Exception {
    // stop accepting new tasks
    cpuPool.shutdown();
    writerPool.shutdown();
    permitPoolExecutor.shutdown();

    // wait a bit for tasks to finish
    await(cpuPool, "cpuPool");
    await(writerPool, "writerPool");
    await(permitPoolExecutor, "permitPoolExecutor");
  }

  private static void await(ExecutorService es, String name) throws InterruptedException {
    if (!es.awaitTermination(1, TimeUnit.MINUTES)) {
      es.shutdownNow();
      if (!es.awaitTermination(30, TimeUnit.SECONDS)) {
        System.err.println("Executor did not terminate: " + name);
      }
    }
  }
}
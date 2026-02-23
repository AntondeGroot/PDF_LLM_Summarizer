package nl.adgroot.pdfsummarizer.notes;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import nl.adgroot.pdfsummarizer.llm.LlmMetrics;

public class ProgressTracker {
  private final int totalPages;
  private final AtomicInteger donePages = new AtomicInteger();
  private final Instant startAll = Instant.now();

  // Rolling totals for averages (thread-safe & fast under contention)
  private final LongAdder sumPromptEvalNs = new LongAdder();
  private final LongAdder sumEvalNs = new LongAdder();
  private final LongAdder sumPromptTokens = new LongAdder();
  private final LongAdder sumEvalTokens = new LongAdder();
  private final LongAdder sumTotalNs = new LongAdder();

  // Keep the last page metrics for display (atomic snapshot)
  private final AtomicReference<LlmMetrics> lastMetrics = new AtomicReference<>();

  public ProgressTracker(int totalPages) {
    this.totalPages = totalPages;
  }

  /** Backwards compatible: if you don’t have metrics, call this. */
  public void finishPage() {
    donePages.incrementAndGet();
  }

  /** Preferred: call this with Ollama metrics for richer status output. */
  public void finishPage(LlmMetrics metrics) {
    donePages.incrementAndGet();

    if (metrics != null) {
      lastMetrics.set(metrics);

      sumPromptEvalNs.add(metrics.promptEvalDurationNs());
      sumEvalNs.add(metrics.evalDurationNs());
      sumPromptTokens.add(metrics.promptEvalCount());
      sumEvalTokens.add(metrics.evalCount());
      sumTotalNs.add(metrics.totalDurationNs());
    }
  }

  public String formatStatus(long lastPageMillis) {
    int done = donePages.get();
    int remaining = totalPages - done;

    Duration elapsed = Duration.between(startAll, Instant.now());
    double elapsedSec = Math.max(0.001, elapsed.toMillis() / 1000.0);

    double throughput = done / elapsedSec; // pages/sec
    long etaSec = (throughput <= 0) ? 0 : (long) Math.ceil(remaining / throughput);

    double pct = (done * 100.0) / totalPages;

    // Averages across completed pages (weighted by duration)
    double avgPromptTokPerSec = tokPerSec(sumPromptTokens.sum(), sumPromptEvalNs.sum());
    double avgEvalTokPerSec = tokPerSec(sumEvalTokens.sum(), sumEvalNs.sum());

    // Last-page metrics
    LlmMetrics lm = lastMetrics.get();
    String llmPart = "";
    if (lm != null) {
      llmPart = String.format("""
      
      LLM (last page):
        Generation : %6.1f tok/s | %5.1fs | %4d tokens
        Prompt     : %6.1f tok/s | %5.1fs | %4d tokens
      """,
          lm.evalTokensPerSecond(),
          lm.evalDurationNs() / 1_000_000_000.0,
          lm.evalCount(),
          lm.promptTokensPerSecond(),
          lm.promptEvalDurationNs() / 1_000_000_000.0,
          lm.promptEvalCount()
      );
    }

    String avgPart = "";
    if (sumEvalNs.sum() > 0 || sumPromptEvalNs.sum() > 0) {
      avgPart = String.format("""
      LLM (average so far):
        Generation : %6.1f tok/s
        Prompt     : %6.1f tok/s
      """,
          avgEvalTokPerSec,
          avgPromptTokPerSec
      );
    }

    return String.format(
        "Page %d/%d (%.2f%%) | last=%s | elapsed=%s | throughput=%.2f pages/s | ETA=%s%s%s",
        done, totalPages, pct,
        fmtDuration(Duration.ofMillis(lastPageMillis)),
        fmtDuration(elapsed),
        throughput,
        fmtDuration(Duration.ofSeconds(etaSec)),
        llmPart,
        avgPart
    );
  }

  private static double tokPerSec(long tokens, long durationNs) {
    if (tokens <= 0 || durationNs <= 0) return 0.0;
    return tokens / (durationNs / 1_000_000_000.0);
  }

  private static String fmtDuration(Duration d) {
    long s = d.getSeconds();
    long h = s / 3600;
    long m = (s % 3600) / 60;
    long sec = s % 60;
    if (h > 0) return String.format("%dh %02dm %02ds", h, m, sec);
    if (m > 0) return String.format("%dm %02ds", m, sec);
    return String.format("%ds", sec);
  }
}
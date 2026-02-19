package nl.adgroot.pdfsummarizer.notes;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ProgressTracker {
  private final int totalPages;
  private int donePages = 0;

  private final List<Long> pageMillis = new ArrayList<>();
  private double emaMillis = -1;             // exponential moving average
  private final double alpha;                // 0..1, higher = more responsive

  private Instant startAll = Instant.now();

  public ProgressTracker(int totalPages) {
    this(totalPages, 0.25);                  // 0.2–0.3 is usually nice
  }

  public ProgressTracker(int totalPages, double alpha) {
    this.totalPages = totalPages;
    this.alpha = alpha;
  }

  public PageTimer startPage() {
    return new PageTimer();
  }

  public void finishPage(long millis) {
    donePages++;
    pageMillis.add(millis);
    if (emaMillis < 0) emaMillis = millis;
    else emaMillis = alpha * millis + (1 - alpha) * emaMillis;
  }

  public int done() { return donePages; }
  public int total() { return totalPages; }

  public Duration elapsedAll() {
    return Duration.between(startAll, Instant.now());
  }

  public Duration etaRemaining() {
    int remaining = totalPages - donePages;
    if (remaining <= 0 || emaMillis < 0) return Duration.ZERO;
    long etaMs = (long) (remaining * emaMillis);
    return Duration.ofMillis(etaMs);
  }

  public double avgMillis() {
    if (pageMillis.isEmpty()) return 0;
    long sum = 0;
    for (Long m : pageMillis) sum += m;
    return (double) sum / pageMillis.size();
  }

  public double emaMillis() {
    return Math.max(0, emaMillis);
  }

  public String formatStatus(long lastPageMillis) {
    double pct = (donePages * 100.0) / totalPages;
    return String.format(
        "Page %d/%d (%.2f%%) | last=%s | avg=%s | ema=%s | elapsed=%s | ETA=%s",
        donePages, totalPages, pct,
        fmtMillis(lastPageMillis),
        fmtMillis((long) avgMillis()),
        fmtMillis((long) emaMillis()),
        fmtDuration(elapsedAll()),
        fmtDuration(etaRemaining())
    );
  }

  private static String fmtMillis(long ms) {
    return fmtDuration(Duration.ofMillis(ms));
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

  public static final class PageTimer implements AutoCloseable {
    private final long startNs = System.nanoTime();
    private long elapsedMs = -1;

    public long elapsedMs() {
      if (elapsedMs >= 0) return elapsedMs;
      return (System.nanoTime() - startNs) / 1_000_000;
    }

    @Override public void close() {
      elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
    }
  }
}

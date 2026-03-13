package nl.adgroot.pdfsummarizer.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import nl.adgroot.pdfsummarizer.llm.records.LlmMetrics;
import org.junit.jupiter.api.Test;

class ProgressTrackerTest {

  // ── finishPage / finishBatch counting ───────────────────────────────────

  @Test
  void finishPage_incrementsByOne() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishPage();
    assertTrue(tracker.formatStatus(0).startsWith("Page 1/10"));
  }

  @Test
  void finishPage_calledMultipleTimes_accumulatesCorrectly() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishPage();
    tracker.finishPage();
    tracker.finishPage();
    assertTrue(tracker.formatStatus(0).startsWith("Page 3/10"));
  }

  @Test
  void finishBatch_incrementsByBatchSize() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishBatch(5, null);
    assertTrue(tracker.formatStatus(0).startsWith("Page 5/10"));
  }

  @Test
  void finishBatch_multipleBatches_accumulatesCorrectly() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishBatch(3, null);
    tracker.finishBatch(4, null);
    assertTrue(tracker.formatStatus(0).startsWith("Page 7/10"));
  }

  @Test
  void finishPage_withMetrics_delegatesToFinishBatch() {
    LlmMetrics metrics = new LlmMetrics(1_000_000_000L, 200_000_000L, 800_000_000L, 50, 200);
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishPage(metrics);
    assertTrue(tracker.formatStatus(0).startsWith("Page 1/10"));
  }

  // ── ETA never goes negative ──────────────────────────────────────────────

  @Test
  void formatStatus_whenAllPagesComplete_etaIsZero() {
    ProgressTracker tracker = new ProgressTracker(5);
    for (int i = 0; i < 5; i++) tracker.finishPage();

    String status = tracker.formatStatus(1000);
    // remaining = 0, so ETA must be 0s
    assertTrue(status.contains("ETA=0s"), "ETA should be 0s when done, got: " + status);
  }

  @Test
  void formatStatus_pctNeverExceeds100_whenDonePagesEqualsTotal() {
    ProgressTracker tracker = new ProgressTracker(5);
    for (int i = 0; i < 5; i++) tracker.finishPage();

    String status = tracker.formatStatus(0);
    assertTrue(status.contains("Page 5/5"), "Expected Page 5/5 when all pages done, got: " + status);
  }

  // ── formatStatus output structure ────────────────────────────────────────

  @Test
  void formatStatus_containsExpectedFields() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishPage();
    String status = tracker.formatStatus(500);

    assertTrue(status.contains("Page 1/10"));
    assertTrue(status.contains("last="));
    assertTrue(status.contains("elapsed="));
    assertTrue(status.contains("throughput="));
    assertTrue(status.contains("ETA="));
  }

  @Test
  void formatStatus_withNullMetrics_doesNotContainLlmSection() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishBatch(1, null);
    String status = tracker.formatStatus(0);

    assertFalse(status.contains("LLM (last page):"), "LLM section should be absent without metrics");
    assertFalse(status.contains("LLM (average so far):"), "Average section should be absent without metrics");
  }

  @Test
  void formatStatus_withMetrics_containsLlmSection() {
    LlmMetrics metrics = new LlmMetrics(1_000_000_000L, 200_000_000L, 800_000_000L, 50, 200);
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishBatch(1, metrics);
    String status = tracker.formatStatus(0);

    assertTrue(status.contains("LLM (last page):"), "LLM section should be present with metrics");
    assertTrue(status.contains("LLM (average so far):"), "Average section should be present with metrics");
  }

  // ── Duration formatting ──────────────────────────────────────────────────

  @Test
  void formatStatus_lastPageDuration_formattedAsSeconds() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishPage();
    String status = tracker.formatStatus(5_000); // 5 seconds
    assertTrue(status.contains("last=5s"), "Expected last=5s, got: " + status);
  }

  @Test
  void formatStatus_lastPageDuration_formattedAsMinutesAndSeconds() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishPage();
    String status = tracker.formatStatus(90_000); // 1m 30s
    assertTrue(status.contains("last=1m 30s"), "Expected last=1m 30s, got: " + status);
  }

  @Test
  void formatStatus_lastPageDuration_formattedAsHoursMinutesSeconds() {
    ProgressTracker tracker = new ProgressTracker(10);
    tracker.finishPage();
    String status = tracker.formatStatus(3_661_000); // 1h 1m 1s
    assertTrue(status.contains("last=1h 01m 01s"), "Expected last=1h 01m 01s, got: " + status);
  }
}
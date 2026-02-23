package nl.adgroot.pdfsummarizer.llm;

public record LlmMetrics(
    long totalDurationNs,
    long promptEvalDurationNs,
    long evalDurationNs,
    int promptEvalCount,
    int evalCount
) {
  public double evalTokensPerSecond() {
    return evalDurationNs == 0 ? 0 :
        (evalCount / (evalDurationNs / 1_000_000_000.0));
  }

  public double promptTokensPerSecond() {
    return promptEvalDurationNs == 0 ? 0 :
        (promptEvalCount / (promptEvalDurationNs / 1_000_000_000.0));
  }
}
package nl.adgroot.pdfsummarizer.prompts;

/**
 * Holds prompt templates for either the single-stage or three-stage pipeline.
 * Exactly one of {single} or {step1, step2, step3} should be non-null.
 */
public record PromptTemplates(
    PromptTemplate single,
    PromptTemplate step1,
    PromptTemplate step2,
    PromptTemplate step3
) {
  /** Returns the primary template for token estimation (single or step1 for three-stage). */
  public PromptTemplate primary() {
    return single != null ? single : step1;
  }
}
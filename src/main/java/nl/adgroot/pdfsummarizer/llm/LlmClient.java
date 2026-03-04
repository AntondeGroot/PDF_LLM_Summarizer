package nl.adgroot.pdfsummarizer.llm;

import java.util.concurrent.CompletableFuture;
import nl.adgroot.pdfsummarizer.llm.records.LlmResult;

public interface LlmClient {
  CompletableFuture<LlmResult> generateAsync(String prompt);
  String getName(); // optional, for logging
  String getUrl();
}

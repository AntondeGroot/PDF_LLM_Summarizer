package nl.adgroot.pdfsummarizer.pipeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
public interface BatchPipeline {
  CompletableFuture<Map<Integer, List<String>>> processBatchAsync(
      BatchContext ctx,
      String chapterTitle,
      List<PdfObject> batch
  );
}

package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.LlmClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.notes.records.CardsPage;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplates;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class ChapterProcessor {

  private static final AppLogger log = AppLogger.getLogger(ChapterProcessor.class);

  public CompletableFuture<Void> processChapterAsync(
      Chapter chapter,
      List<PdfObject> pages,
      String topic,
      BatchPipeline pipeline,
      List<LlmClient> llms,
      ServerPermitPool permitPool,
      ExecutorService permitPoolExecutor,
      ExecutorService cpuPoolExecutor,
      ExecutorService writerPool,
      PromptTemplates prompts,
      AppConfig cfg,
      ProgressTracker tracker,
      NotesWriter writer,
      Path outDir
  ) {

    final String chapterHeader = chapter.header;
    log.info("Scheduling chapter: " + chapterHeader);

    // Pages for this chapter, stable order
    List<PdfObject> pagesInChapter = pages.stream()
        .filter(p -> chapterHeader.equals(p.getChapter()))
        .toList();

    // Batch by tokens
    int maxTokensPerChunk = resolveMaxTokensPerChunk(cfg);

    // Compute base prompt tokens by rendering the primary template with empty content.
    int basePromptTokens = estimateBasePromptTokens(prompts, cfg, topic, chapterHeader);

    List<List<PdfObject>> batches = cfg.ollama.localBatching
        ? splitIntoBatchesByEstimatedTokens(chapterHeader, pagesInChapter, maxTokensPerChunk,
        basePromptTokens)
        : pagesInChapter.stream().map(List::of).toList();

    List<CompletableFuture<Void>> batchFutures = new ArrayList<>(batches.size());

    for (List<PdfObject> batch : batches) {
      CompletableFuture<Void> bf = pipeline.processBatchAsync(
              llms,
              permitPool,
              permitPoolExecutor,
              cpuPoolExecutor,
              prompts,
              cfg,
              topic,
              chapterHeader,
              batch,
              tracker,
              outDir
          )
          .thenAcceptAsync(cardsBySelectedIndex -> {

            for (PdfObject p : batch) {
              List<String> cards = cardsBySelectedIndex.getOrDefault(p.getIndex(), List.of());
              p.setCards(cards);

              CardsPage perPage = new CardsPage(topic, chapterHeader);
              for (String card : cards) {
                perPage.addCard(card);
              }
              p.setNotes(perPage.hasContent() ? perPage.toString() : "");
            }

          }, writerPool)
          .whenComplete((res, ex) -> {
            if (ex != null) {
              log.error("Batch task failed in chapter '" + chapterHeader + "': " + ex);
            }
          });

      batchFutures.add(bf);
    }

    // After all batches done: write chapter file (single write on writerPool)
    return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
        .thenAcceptAsync(v -> {

          CardsPage chapterCards = new CardsPage(topic, chapterHeader);

          // Add cards in chapter order from each PdfObject
          for (PdfObject p : pagesInChapter) {
            for (String card : p.getCards()) {
              chapterCards.addCard(card);
            }
          }

          // Write chapter output file
          try {
            if (chapterCards.hasContent()) {
              writer.writeCard(outDir, chapterCards);
              log.info("WROTE chapter: " + chapterHeader + " -> " + outDir.toAbsolutePath());
            } else if (!pagesInChapter.isEmpty()) {
              // a chapter was planned but the prompt resulted in no notes
              log.info("No notes were taken for " + chapterHeader);
            }
            // a chapter was not planned: for example during a preview run
            // that means that "no notes taken" is perfectly okay!
          } catch (IOException e) {
            throw new RuntimeException(e);
          }

        }, writerPool);
  }

  /**
   * Splits pages into batches so sum(ceil(len/4)) <= maxTokensPerChunk. Always puts at least 1 page
   * into a batch.
   */
  private static List<List<PdfObject>> splitIntoBatchesByEstimatedTokens(
      String chapterHeader,
      List<PdfObject> pages,
      int maxTokensPerChunk,
      int basePromptTokens
  ) {
    if (pages == null || pages.isEmpty()) {
      return List.of();
    }

    int maxTokens = Math.max(1, maxTokensPerChunk);

    List<List<PdfObject>> out = new ArrayList<>();
    List<PdfObject> current = new ArrayList<>();
    int currentTokens = 0;
    int currentChars = 0;

    for (PdfObject p : pages) {
      String text = p.getTextReadFromPdf();
      int pageChars = (text == null) ? 0 : text.length();
      int pageTokens = estimateTokens(text);

      // Always at least one page per batch
      if (current.isEmpty()) {
        current.add(p);
        currentTokens = pageTokens;
        currentChars = pageChars;
        continue;
      }

      // Would exceed?
      if (currentTokens + pageTokens > maxTokens) {
        logBatch(chapterHeader, out.size() + 1, current, currentChars, currentTokens,
            basePromptTokens + currentTokens);

        out.add(current);

        current = new ArrayList<>();
        current.add(p);
        currentTokens = pageTokens;
        currentChars = pageChars;
        continue;
      }

      current.add(p);
      currentTokens += pageTokens;
      currentChars += pageChars;
    }

    if (!current.isEmpty()) {
      logBatch(chapterHeader, out.size() + 1, current, currentChars, currentTokens,
          basePromptTokens + currentTokens);
      out.add(current);
    }

    return out;
  }

  private static void logBatch(
      String chapterHeader,
      int batchNr,
      List<PdfObject> batch,
      int chars,
      int contentTokens,
      int promptTokens
  ) {
    List<Integer> indexes = batch.stream().map(PdfObject::getIndex).toList();

    log.info(
        "BATCH chapter='%s' batch=%d pages=%s chars=%d contentTokens≈%d promptTokens≈%d%n",
        chapterHeader,
        batchNr,
        indexes,
        chars,
        contentTokens,
        promptTokens);
  }

  /**
   * Token estimate: ceil(length/4).
   */
  private static int estimateTokens(String s) {
    if (s == null || s.isEmpty()) {
      return 0;
    }
    return (s.length() + 3) / 4;
  }

  /**
   * Estimate base prompt token count by rendering the primary template with empty content.
   */
  private static int estimateBasePromptTokens(
      PromptTemplates prompts,
      AppConfig cfg,
      String topic,
      String chapterHeader
  ) {
    String base = prompts.primary().render(Map.of(
        "topic", topic,
        "section", chapterHeader,
        "maxCards", String.valueOf(cfg.cards.maxCardsPerChunk),
        "maxConcepts", String.valueOf(cfg.cards.maxConceptsPerPage),
        "content", ""
    ));
    return estimateTokens(base);
  }

  private static int resolveMaxTokensPerChunk(AppConfig cfg) {
    int maxTokens = cfg.chunking.maxTokensPerChunk;
    return Math.max(1, maxTokens);
  }
}
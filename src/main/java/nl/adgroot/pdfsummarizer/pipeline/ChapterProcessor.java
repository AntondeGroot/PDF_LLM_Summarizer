package nl.adgroot.pdfsummarizer.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import nl.adgroot.pdfsummarizer.AppLogger;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.records.CardsPage;
import nl.adgroot.pdfsummarizer.pdf.parsing.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.parsing.Chapter;

public class ChapterProcessor {

  private static final AppLogger log = AppLogger.getLogger(ChapterProcessor.class);

  public CompletableFuture<Void> processChapterAsync(
      Chapter chapter,
      List<PdfObject> pages,
      BatchPipeline pipeline,
      BatchContext ctx,
      ExecutorService writerPool,
      NotesWriter writer
  ) {
    final String chapterHeader = chapter.header;
    log.info("Scheduling chapter: " + chapterHeader);

    List<PdfObject> pagesInChapter = pages.stream()
        .filter(p -> chapterHeader.equals(p.getChapter()))
        .toList();

    List<List<PdfObject>> batches = buildBatches(ctx, chapterHeader, pagesInChapter);

    List<CompletableFuture<Void>> batchFutures = new ArrayList<>(batches.size());
    for (List<PdfObject> batch : batches) {
      CompletableFuture<Void> bf = pipeline.processBatchAsync(ctx, chapterHeader, batch)
          .thenAcceptAsync(cards -> applyBatchResults(cards, batch, chapterHeader, ctx.topic()),
              writerPool)
          .whenComplete((res, ex) -> {
            if (ex != null) log.error("Batch failed in chapter '" + chapterHeader + "': " + ex);
          });
      batchFutures.add(bf);
    }

    return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
        .thenAcceptAsync(v -> writeChapterFile(pagesInChapter, chapterHeader, ctx, writer),
            writerPool);
  }

  private static void applyBatchResults(
      Map<Integer, List<String>> cardsByIndex,
      List<PdfObject> batch,
      String chapterHeader,
      String topic
  ) {
    for (PdfObject p : batch) {
      List<String> cards = cardsByIndex.getOrDefault(p.getIndex(), List.of());
      p.setCards(cards);

      CardsPage perPage = new CardsPage(topic, chapterHeader);
      cards.forEach(perPage::addCard);
      p.setNotes(perPage.hasContent() ? perPage.toString() : "");
    }
  }

  private static void writeChapterFile(
      List<PdfObject> pagesInChapter,
      String chapterHeader,
      BatchContext ctx,
      NotesWriter writer
  ) {
    CardsPage chapterCards = new CardsPage(ctx.topic(), chapterHeader);
    for (PdfObject p : pagesInChapter) {
      p.getCards().forEach(chapterCards::addCard);
    }

    try {
      if (chapterCards.hasContent()) {
        writer.writeCard(ctx.outDir(), chapterCards);
        log.info("WROTE chapter: " + chapterHeader + " -> " + ctx.outDir().toAbsolutePath());
      } else if (!pagesInChapter.isEmpty()) {
        log.info("No notes were taken for " + chapterHeader);
      }
      // empty pagesInChapter = preview run with no LLM calls — silence is correct
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static List<List<PdfObject>> buildBatches(
      BatchContext ctx, String chapterHeader, List<PdfObject> pagesInChapter
  ) {
    if (!ctx.cfg().ollama.localBatching) {
      return pagesInChapter.stream().map(List::of).toList();
    }
    int maxTokens = Math.max(1, ctx.cfg().chunking.maxTokensPerChunk);
    int basePromptTokens = estimateBasePromptTokens(ctx, chapterHeader);
    return splitIntoBatchesByEstimatedTokens(chapterHeader, pagesInChapter, maxTokens,
        basePromptTokens);
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
  private static int estimateBasePromptTokens(BatchContext ctx, String chapterHeader) {
    String base = ctx.prompts().primary().render(Map.of(
        "topic", ctx.topic(),
        "section", chapterHeader,
        "maxCards", String.valueOf(ctx.cfg().cards.maxCardsPerChunk),
        "maxConcepts", String.valueOf(ctx.cfg().cards.maxConceptsPerPage),
        "content", ""
    ));
    return estimateTokens(base);
  }

}
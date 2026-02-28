package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import nl.adgroot.pdfsummarizer.PagePipeline.PageResult;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.records.CardsPage;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Chapter;
import nl.adgroot.pdfsummarizer.text.Page;

public class ChapterProcessor {

  /**
   * Processes one chapter:
   * - schedules all pages in the chapter via PagePipeline
   * - waits for all pages
   * - orders results by page index in chapter
   * - writes the chapter CardsPage on writerPool
   * - fills cardsPagesByIndex with ONE CardsPage PER SELECTED PAGE (keyed by content index)
   */
  public CompletableFuture<Void> processChapterAsync(
      Chapter chapter,
      ParsedPDF parsedPdf,
      String topic,
      PagePipeline pipeline,
      List<OllamaClient> llms,
      ServerPermitPool permitPool,
      ExecutorService permitPoolExecutor,
      ExecutorService cpuPoolExecutor,
      ExecutorService writerPool,
      PromptTemplate promptTemplate,
      AppConfig cfg,
      ProgressTracker tracker,
      NotesWriter writer,
      Path outDir,
      Map<Integer, CardsPage> cardsPagesByIndex
  ) {

    final String chapterHeader = chapter.header;

    System.out.println("Scheduling chapter: " + chapterHeader);

    // Chapter container (for writing the chapter file)
    CardsPage chapterCards = new CardsPage(topic, chapterHeader);

    // Pages for this chapter
    List<Page> pagesInChapter = parsedPdf.getContent()
        .stream()
        .filter(p -> p.chapter.equals(chapterHeader))
        .toList();

    List<CompletableFuture<PageResult>> pageFutures = new ArrayList<>(pagesInChapter.size());

    for (int i = 0; i < pagesInChapter.size(); i++) {
      int pageIndexInChapter = i;
      Page page = pagesInChapter.get(i);
      int pageNr = page.pageNr;

      CompletableFuture<PageResult> pf = pipeline.processPageAsync(
          llms,
          permitPool,
          permitPoolExecutor,
          cpuPoolExecutor,
          promptTemplate,
          cfg,
          topic,
          chapterHeader,
          pageIndexInChapter,
          pageNr,
          pagesInChapter.size(),
          page,
          tracker
      ).whenComplete((res, ex) -> {
        if (ex != null) {
          synchronized (System.out) {
            System.out.println("Page task failed in chapter '" + chapterHeader + "': " + ex);
          }
        } else {
          System.out.println(res.cards());
          synchronized (System.out) {
            System.out.println(tracker.formatStatus(res.millis()));
          }
        }
      });

      pageFutures.add(pf);
    }

    return CompletableFuture.allOf(pageFutures.toArray(new CompletableFuture[0]))
        .thenApply(v -> pageFutures.stream()
            .map(CompletableFuture::join)
            .sorted(Comparator.comparingInt(PageResult::index))
            .toList()
        )
        .thenAcceptAsync(results -> {

          // 1) Build the chapter cards (for file output)
          for (PageResult r : results) {
            for (String card : r.cards()) {
              chapterCards.addCard(card);
            }
          }

          // 2) FIX: Build preview notes per PAGE INDEX (0..n-1),
          // not per chapter. This makes preview always 2*n pages.
          for (PageResult r : results) {
            int contentIndex = indexOfPageNr(parsedPdf, r.pageNr());
            if (contentIndex < 0) continue;

            CardsPage perPage = new CardsPage(topic, chapterHeader);
            for (String card : r.cards()) perPage.addCard(card);

            cardsPagesByIndex.put(contentIndex, perPage);
          }

          // 3) Write chapter output file
          try {
            if (chapterCards.hasContent()) {
              writer.writeCard(outDir, chapterCards);
              synchronized (System.out) {
                System.out.println("WROTE chapter: " + chapterHeader + " -> " + outDir.toAbsolutePath());
              }
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }

        }, writerPool);
  }

  /**
   * Find the index (0..content.size-1) of a page by pageNr.
   * In preview mode, parsedPdf.getContent() has already been trimmed to the selected pages,
   * so these indexes match the pdfPages list used for composing the preview PDF.
   */
  private static int indexOfPageNr(ParsedPDF parsedPdf, int pageNr) {
    List<Page> content = parsedPdf.getContent();
    for (int i = 0; i < content.size(); i++) {
      if (content.get(i).pageNr == pageNr) return i;
    }
    return -1;
  }
}
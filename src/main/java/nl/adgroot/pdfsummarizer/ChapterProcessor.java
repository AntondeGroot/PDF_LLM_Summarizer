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
import nl.adgroot.pdfsummarizer.notes.CardsPage;
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
   * - stores the CardsPage into cardsPagesByIndex for preview composition
   *
   * Returns a future that completes when the chapter has been written (or fails).
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

    // Prepare chapter container up front
    CardsPage cardsPage = new CardsPage();
    cardsPage.addChapter(chapterHeader);
    cardsPage.addTopic(topic);

    // Pages for this chapter (keep original order from ParsedPDF)
    List<Page> pagesInChapter = parsedPdf.getContent()
        .stream()
        .filter(p -> p.chapter.equals(chapterHeader))
        .toList();

    List<CompletableFuture<PageResult>> pageFutures = new ArrayList<>(pagesInChapter.size());

    for (int i = 0; i < pagesInChapter.size(); i++) {
      int pageIndexInChapter = i; // stable for ordering
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

    // When this chapter's pages are all finished, write it (writerPool).
    return CompletableFuture.allOf(pageFutures.toArray(new CompletableFuture[0]))
        .thenApply(v -> pageFutures.stream()
            .map(CompletableFuture::join) // safe after allOf
            .sorted(Comparator.comparingInt(PageResult::index))
            .toList()
        )
        .thenAcceptAsync(results -> {
          for (PageResult r : results) {
            for (String card : r.cards()) {
              cardsPage.addCard(card);
            }
          }

          try {
            if (cardsPage.hasContent()) {
              // Keep your existing preview mapping behavior (first result pageNr)
              cardsPagesByIndex.put(results.getFirst().pageNr(), cardsPage);

              writer.writeCard(outDir, cardsPage);

              synchronized (System.out) {
                System.out.println("WROTE chapter: " + chapterHeader + " -> " + outDir.toAbsolutePath());
              }
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }, writerPool);
  }
}
package nl.adgroot.pdfsummarizer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import nl.adgroot.pdfsummarizer.PagePipeline.PageResult;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.llm.ServerPermitPool;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.notes.records.CardsPage;
import nl.adgroot.pdfsummarizer.pdf.PdfObject;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class ChapterProcessor {

  public CompletableFuture<Void> processChapterAsync(
      Chapter chapter,
      List<PdfObject> pages,
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
      Path outDir
  ) {

    final String chapterHeader = chapter.header;
    System.out.println("Scheduling chapter: " + chapterHeader);

    CardsPage chapterCards = new CardsPage(topic, chapterHeader);

    // Pages for this chapter, stable order
    List<PdfObject> pagesInChapter = pages.stream()
        .filter(p -> chapterHeader.equals(p.getChapter()))
        .toList();

    List<CompletableFuture<PageResult>> pageFutures = new ArrayList<>(pagesInChapter.size());

    for (int i = 0; i < pagesInChapter.size(); i++) {
      int pageIndexInChapter = i;
      PdfObject pdfObject = pagesInChapter.get(i);

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
          pdfObject.getIndex(),     // logging/debug only
          pagesInChapter.size(),
          pdfObject.getText(),
          tracker
      ).whenComplete((res, ex) -> {
        if (ex != null) {
          synchronized (System.out) {
            System.out.println("Page task failed in chapter '" + chapterHeader + "': " + ex);
          }
        } else {
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

          // 1) Chapter-level file
          for (PageResult r : results) {
            for (String card : r.cards()) {
              chapterCards.addCard(card);
            }
          }

          // 2) Per-page notes stored directly on PdfObject
          for (PageResult r : results) {
            PdfObject pdfObject = pagesInChapter.get(r.index());

            CardsPage perPage = new CardsPage(topic, chapterHeader);
            for (String card : r.cards()) {
              perPage.addCard(card);
            }

            if (perPage.hasContent()) {
              pdfObject.setNotes(perPage.toString());
            }
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
}
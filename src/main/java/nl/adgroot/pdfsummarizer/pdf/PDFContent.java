package nl.adgroot.pdfsummarizer.pdf;

import static nl.adgroot.pdfsummarizer.text.TableOfContentConverter.convert;

import java.util.ArrayList;
import java.util.List;
import nl.adgroot.pdfsummarizer.text.Chapter;
import nl.adgroot.pdfsummarizer.text.Page;

public class PDFContent {
  public List<Chapter> tableOfContent;
  public List<Page> content;

  public PDFContent(List<String> pages){
    // determine Table Of Content Pages
    int TOC_begin = PDFUtil.getTableOfContentFirstPage(pages);
    int TOC_end = PDFUtil.getTableOfContentLastPage(pages, TOC_begin);

    StringBuilder TOC = new StringBuilder();
    for (int i = TOC_begin; i <= TOC_end; i++) {
      TOC.append(pages.get(i));
    }

    tableOfContent = convert(TOC.toString(), pages.size());

    // determine content without TOC
    List<String> pages2 = PDFUtil.getStringPagesWithoutTOC(pages, tableOfContent);
    List<Page> pages3 = new ArrayList<>();
    int offset = -tableOfContent.getFirst().start;

    int chapterIdx = 0;
    Chapter current = tableOfContent.getFirst();
    for (int i = 0; i < pages2.size(); i++) {
      int pdfPageNr = i + 1;

      // Move to next chapter if this PDF page is at/after the next chapter's PDF start
      while (chapterIdx + 1 < tableOfContent.size()) {
        int nextPdfStart = tableOfContent.get(chapterIdx + 1).start + offset;
        if (pdfPageNr >= nextPdfStart) {
          chapterIdx++;
          current = tableOfContent.get(chapterIdx);
        } else {
          break;
        }
      }

      Page page = new Page(pages.get(i)); // <-- adapt to your Page constructor

      int currentPdfStart = current.start + offset;
      int currentPdfEnd   = current.end + offset;

      // Only attach chapter if we're within the mapped range
      if (pdfPageNr >= currentPdfStart && pdfPageNr <= currentPdfEnd) {
        page.chapter = current.title; // or page.setChapter(current)
        pages3.add(page);
      }
    }
    content = pages3;
  }

  @Override
  public String toString() {
    return "TOC: "+tableOfContent+"\nContent: "+content;
  }
}

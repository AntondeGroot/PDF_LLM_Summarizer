package nl.adgroot.pdfsummarizer.pdf;

import static nl.adgroot.pdfsummarizer.text.TableOfContentConverter.convert;

import java.util.ArrayList;
import java.util.List;
import nl.adgroot.pdfsummarizer.text.Chapter;
import nl.adgroot.pdfsummarizer.text.Page;

public class ParsedPDF {
  private List<Chapter> tableOfContent;
  private List<Page> content;
  private int offset;

  public ParsedPDF(List<String> pages, int nrOfLinesUsedForContext){
    // determine Table Of Content Pages
    int TOC_begin = PDFUtil.getTableOfContentFirstPage(pages);
    int TOC_end = PDFUtil.getTableOfContentLastPage(pages, TOC_begin);

    StringBuilder TOC = new StringBuilder();
    for (int i = TOC_begin; i <= TOC_end; i++) {
      TOC.append(pages.get(i));
    }

    tableOfContent = convert(TOC.toString());

    // determine content without TOC
    pages = pages.subList(TOC_end+1, pages.size());
    pages = PDFUtil.getStringPagesWithoutTOC(pages, tableOfContent);
    tableOfContent.getLast().end = pages.size(); // this value was not yet determined, and there is not yet a good way to determine it.
    List<Page> pages3 = new ArrayList<>();
    offset = -tableOfContent.getFirst().start;

    int chapterIdx = 0;
    Chapter current = tableOfContent.getFirst();
    for (int i = 0; i < pages.size(); i++) {
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

      Page page = new Page(pages.get(i));

      int currentPdfStart = current.start + offset;
      int currentPdfEnd   = current.end + offset;

      // Only attach chapter if we're within the mapped range
      if (pdfPageNr >= currentPdfStart && pdfPageNr <= currentPdfEnd) {
        page.chapter = current.title;
        pages3.add(page);
      }
    }
    content = pages3;

    if(nrOfLinesUsedForContext>0){
      setContext(nrOfLinesUsedForContext);
    }
  }

  public List<Chapter> getTableOfContent(){return tableOfContent;}

  public List<Page> getContent(){return content;}

  public void setContent(List<Page> content){
    this.content = content;
  }

  public void setContext(int nrLinesOfContext){
    for (Chapter chapter: tableOfContent){
      for (int i = chapter.start+offset; i < chapter.end+offset-1; i++) {
        Page page = content.get(i);
        if (i>chapter.start+offset){
          page.setContextBefore(content.get(i-1).getLastLines(nrLinesOfContext));
        }
        if((i<chapter.end+offset) && (i<content.size()-1)){
          page.setContextAfter(content.get(i+1).getFirstLines(nrLinesOfContext));
        }
      }
    }
  }
}

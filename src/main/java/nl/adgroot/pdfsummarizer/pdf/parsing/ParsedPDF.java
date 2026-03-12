package nl.adgroot.pdfsummarizer.pdf.parsing;

import static nl.adgroot.pdfsummarizer.pdf.tableOfContents.TableOfContentsConverter.convertTableOfContentsToChapterList;

import java.util.ArrayList;
import java.util.List;
import nl.adgroot.pdfsummarizer.pdf.tableOfContents.TableOfContentsUtil;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class ParsedPDF {
  private final List<Chapter> tableOfContent;
  private final List<String> strippedPages;
  private final int contentStartIndex;

  public ParsedPDF(List<String> pages) {
    int TOC_begin = TableOfContentsUtil.getTableOfContentsFirstPage(pages);
    int TOC_end = TableOfContentsUtil.getTableOfContentsLastPage(pages, TOC_begin);

    StringBuilder TOC = new StringBuilder();
    for (int i = TOC_begin; i <= TOC_end; i++) {
      TOC.append(pages.get(i));
    }
    tableOfContent = convertTableOfContentsToChapterList(TOC.toString(), pages.size());

    int originalPageCount = pages.size();
    List<String> content = pages.subList(TOC_end + 1, pages.size());
    content = TableOfContentsUtil.getStringPagesWithoutTOC(content, tableOfContent);
    contentStartIndex = originalPageCount - content.size();
    strippedPages = new ArrayList<>(content);
  }

  public List<Chapter> getTableOfContent() { return tableOfContent; }
  public List<String> getStrippedPages() { return strippedPages; }
  public int getContentStartIndex() { return contentStartIndex; }
}
package nl.adgroot.pdfsummarizer.pdf;

import static nl.adgroot.pdfsummarizer.text.TableOfContentConverter.convert;

import java.util.ArrayList;
import java.util.List;
import nl.adgroot.pdfsummarizer.text.Chapter;
import nl.adgroot.pdfsummarizer.text.Page;

public class PDFUtil {

  /**
   * Heuristically determines whether the given page content represents a Table of Contents (TOC)
   * page.
   *
   * <p>The method counts non-empty lines
   * and checks whether at least 90% of them end with a page number.
   *
   * @param page the textual content of a single PDF page
   * @return {@code true} if the page is likely a TOC page; {@code false} otherwise
   */
  public static boolean isTableOfContentsPage(String page) {
    if (page == null || page.isBlank()) {
      return false;
    }

    String[] lines = page.split("\\R"); // handles \n, \r\n, etc.

    int totalRelevantLines = 0;
    int tocMatches = 0;

    for (String rawLine : lines) {
      String line = rawLine.trim();

      // Skip empty lines
      if (line.isEmpty()) {
        continue;
      }

      totalRelevantLines++;

      // Regex: ends with page number
      if (line.matches(".*\\s+\\d+$")) {
        tocMatches++;
      }

      if (line.equalsIgnoreCase("table of contents")) {
        tocMatches++;
      }
    }

    if (totalRelevantLines == 0) {
      return false;
    }

    double ratio = (double) tocMatches / totalRelevantLines;

    return ratio >= 0.90;
  }

  static int getTableOfContentFirstPage(List<String> pages) {
    for (int i = 0; i < 20; i++) {
      if (pages.get(i).toLowerCase().contains("table of contents")) {
        return i;
      }
    }
    throw new TableOfContentsException("Could not find table of contents");
  }

  static int getTableOfContentLastPage(List<String> pages, int tocBeginIndex) {
    for (int i = tocBeginIndex + 1; i < pages.size(); i++) {
      if (!isTableOfContentsPage(pages.get(i))) {
        return i - 1;
      }
    }
    throw new TableOfContentsException("Could not find end of table of contents");
  }


  public static List<String> getStringPagesWithoutTOC(List<String> pagesRaw, List<Chapter> tableOfContents){
    String firstChapter = tableOfContents.getFirst().title;
    boolean foundFirstChapter = false;
    List<String> pages = new ArrayList<>();

    for(String pageString: pagesRaw){
      if(pageString.contains(firstChapter)){
        foundFirstChapter = true;
      }
      if(foundFirstChapter){
        pages.add(pageString);
      }
    }
    return pages;
  }
}

package nl.adgroot.pdfsummarizer.pdf.tableOfContents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class TableOfContentsUtil {

  // ends with a page number (allow trailing whitespace)
  private static final Pattern ENDS_WITH_PAGE_NR = Pattern.compile(".*\\s+\\d+\\s*$");

  /**
   * Heuristically determines whether the given page content represents a Table of Contents (TOC)
   * page.
   *
   * <p>The method counts non-empty lines and checks whether at least 90% of them end with a page
   * number.
   *
   * @param page the textual content of a single PDF page
   * @return {@code true} if the page is likely a TOC page; {@code false} otherwise
   */
  public static boolean isPageATableOfContentsPage(String page) {
    if (page == null || page.isBlank()) {
      return false;
    }

    int totalRelevantLines = 0;
    int tocMatches = 0;
    String[] lines = page.split("\\R"); // handles \n, \r\n, etc.

    for (String rawLine : lines) {
      // Normalize: remove control chars that can break regex matching in real PDFs
      String line = rawLine.replaceAll("\\p{C}+", "").trim();
      if (line.isEmpty()) {
        continue;
      }

      totalRelevantLines++;

      if (ENDS_WITH_PAGE_NR.matcher(line).matches() ||
          line.equalsIgnoreCase("contents")) {
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

  public static int getTableOfContentsFirstPage(List<String> pages) {
    int upperBoundTableOfContents = Math.min(pages.size(), 10);
    for (int i = 0; i < upperBoundTableOfContents; i++) {
      if (pages.get(i).toLowerCase().contains("table of contents")) {
        return i;
      }
    }
    for (int i = 0; i < upperBoundTableOfContents; i++) {
      if (pages.get(i).toLowerCase().contains("contents")) {
        return i;
      }
    }

    throw new TableOfContentsException("Could not find table of contents");
  }

  public static int getTableOfContentsLastPage(List<String> pages, int tocBeginIndex) {
    for (int i = tocBeginIndex + 1; i < pages.size(); i++) {
      if (!isPageATableOfContentsPage(pages.get(i))) {
        return i - 1;
      }
    }
    throw new TableOfContentsException("Could not find end of table of contents");
  }

  public static List<String> getStringPagesWithoutTOC(List<String> pagesRaw, List<Chapter> tableOfContents) {
    String firstChapter = tableOfContents.getFirst().header;

    for (int i = 0; i < pagesRaw.size(); i++) {
      String page = pagesRaw.get(i);
      if (page == null) continue;

      if (page.contains(firstChapter)) {
        return pagesRaw.subList(i, pagesRaw.size());
      }
    }
    return new ArrayList<>();
  }
}
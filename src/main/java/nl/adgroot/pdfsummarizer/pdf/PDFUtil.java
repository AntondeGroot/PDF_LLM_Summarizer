package nl.adgroot.pdfsummarizer.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class PDFUtil {

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
  public static boolean isTableOfContentsPage(String page) {
    if (page == null || page.isBlank()) {
      return false;
    }

    String[] lines = page.split("\\R"); // handles \n, \r\n, etc.

    int totalRelevantLines = 0;
    int tocMatches = 0;

    for (String rawLine : lines) {
      if (rawLine == null) continue;

      // Normalize: remove control chars that can break regex matching in real PDFs
      String line = rawLine.replaceAll("\\p{C}+", "").trim();

      if (line.isEmpty()) {
        continue;
      }

      totalRelevantLines++;

      if (ENDS_WITH_PAGE_NR.matcher(line).matches()) {
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

  static int getTableOfContentLastPage(List<String> pages, int tocBeginIndex) {
    for (int i = tocBeginIndex + 1; i < pages.size(); i++) {
      if (!isTableOfContentsPage(pages.get(i))) {
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

      // Do not start content on TOC-like pages (TOC often contains the chapter title)
      if (isTableOfContentsPage(page)) {
        continue;
      }

      if (page.contains(firstChapter)) {
        return pagesRaw.subList(i, pagesRaw.size());
      }
    }
    return new ArrayList<>();
  }
}
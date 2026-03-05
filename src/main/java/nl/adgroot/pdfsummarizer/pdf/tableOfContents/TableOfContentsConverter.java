package nl.adgroot.pdfsummarizer.pdf.tableOfContents;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class TableOfContentsConverter {

  public enum TocStyle {
    CHAPTER,
    NUMBERED,
    UNKNOWN
  }

  // "Chapter 36: Running Simple Node.js Application 131"
  // supports dot leaders: "Chapter 1: Intro .......... 1"
  // group(1)=full chapter title, group(2)=pageNr
  private static final Pattern CHAPTER_HEADING_PATTERN = Pattern.compile(
      "^\\s*(Chapter\\s+\\d+:\\s+.*?)(?:\\s*\\.{2,}\\s*)?\\s+(\\d+)\\s*$",
      Pattern.CASE_INSENSITIVE
  );

  // "4 Some Title 50"
  // supports dot leaders too; IMPORTANT: first group is ONLY digits -> 11.1 won't match
  // group(1)=chapterNr, group(2)=title, group(3)=pageNr
  private static final Pattern NUMBERED_HEADING_PATTERN = Pattern.compile(
      "^\\s*(\\d+)\\s+(.+?)(?:\\s*\\.{2,}\\s*|\\s+)(\\d+)\\s*$"
  );

  // "Appendix A: Extra notes 27" or "APPENDIX B Glossary .... 28"
  // group(2)=pageNr
  private static final Pattern APPENDIX_PATTERN = Pattern.compile(
      "^\\s*(Appendix|Appendices)\\b.*?(?:\\s*\\.{2,}\\s*)?\\s+(\\d+)\\s*$",
      Pattern.CASE_INSENSITIVE
  );

  // Heuristic for NUMBERED TOCs: "non-digit title + trailing page number"
  // e.g. "A Vector Calculus ... 575", "Index 589", "Glossary .... 612"
  // group(2)=pageNr
  private static final Pattern TITLE_WITH_PAGE_PATTERN = Pattern.compile(
      "^\\s*([^\\d\\s].*?)(?:\\s*\\.{2,}\\s*)?\\s+(\\d+)\\s*$"
  );

  public static TocStyle determineMatcherForTableOfContents(String tocPages) {
    if (tocPages == null || tocPages.isBlank()) return TocStyle.UNKNOWN;

    for (String line : tocPages.split("\\R")) {
      String l = line.trim();
      if (l.isEmpty()) continue;
      if (CHAPTER_HEADING_PATTERN.matcher(l).matches()) return TocStyle.CHAPTER;
    }

    for (String line : tocPages.split("\\R")) {
      String l = line.trim();
      if (l.isEmpty()) continue;
      if (NUMBERED_HEADING_PATTERN.matcher(l).matches()) return TocStyle.NUMBERED;
    }

    return TocStyle.UNKNOWN;
  }

  /**
   * Converts TOC text into chapters.
   *
   * @param lastDocumentPage last page number of the PDF (must be > 0)
   */
  public static List<Chapter> convertTableOfContentsToChapterList(String tocPages, int lastDocumentPage) {
    if (tocPages == null || tocPages.isBlank()) return List.of();
    if (lastDocumentPage <= 0) {
      throw new IllegalArgumentException("lastDocumentPage must be > 0");
    }

    TocStyle style = determineMatcherForTableOfContents(tocPages);
    if (style == TocStyle.UNKNOWN) return List.of();

    OptionalInt appendixStartOpt = findAppendixStartPage(tocPages, style);

    final String splitRegex = (style == TocStyle.CHAPTER)
        ? "(?im)(?=^\\s*chapter\\s+\\d+:)"
        : "(?im)(?=^\\s*\\d+\\s+.+?(?:\\s*\\.{2,}\\s*|\\s+)\\d+\\s*$)";

    String[] chunks = tocPages.split(splitRegex);

    List<Chapter> chapters = new ArrayList<>();

    for (String chunk : chunks) {
      if (chunk == null || chunk.isBlank()) continue;

      String[] lines = chunk.split("\\R");
      if (lines.length == 0) continue;

      String firstLine = lines[0].trim();
      if (firstLine.isEmpty()) continue;

      // CHAPTER style appendices are NOT chapters
      if (style == TocStyle.CHAPTER && APPENDIX_PATTERN.matcher(firstLine).matches()) {
        continue;
      }

      if (style == TocStyle.CHAPTER) {
        Matcher m = CHAPTER_HEADING_PATTERN.matcher(firstLine);
        if (m.matches()) {
          String title = cleanTitle(m.group(1).trim());
          int start = Integer.parseInt(m.group(2));

          Chapter chapter = new Chapter(title);
          chapter.start = start;
          chapters.add(chapter);
        }
      } else { // NUMBERED
        Matcher m = NUMBERED_HEADING_PATTERN.matcher(firstLine);
        if (m.matches()) {
          String chapterTitle = cleanTitle(m.group(2).trim());
          int start = Integer.parseInt(m.group(3));

          Chapter chapter = new Chapter(chapterTitle);
          chapter.start = start;
          chapters.add(chapter);
        }
      }
    }

    if (chapters.isEmpty()) return List.of();

    // Fill end pages
    for (int i = 0; i < chapters.size(); i++) {
      Chapter current = chapters.get(i);

      if (i < chapters.size() - 1) {
        int nextStart = chapters.get(i + 1).start;
        current.end = nextStart - 1;
      } else {
        // last chapter: before appendix boundary, otherwise last page of document
        current.end = appendixStartOpt.isPresent()
            ? appendixStartOpt.getAsInt() - 1
            : lastDocumentPage;
      }
    }

    return chapters;
  }

  /**
   * Appendix boundary:
   * - CHAPTER style: explicit "Appendix/Appendices ... <page>"
   * - NUMBERED style heuristic: first non-digit title+page AFTER the last top-level chapter start page.
   *   This avoids false positives from wrapped lines like "Reaction 492" (which appear before the final chapter).
   */
  private static OptionalInt findAppendixStartPage(String tocPages, TocStyle style) {
    if (style == TocStyle.CHAPTER) {
      int min = Integer.MAX_VALUE;
      boolean found = false;

      for (String line : tocPages.split("\\R")) {
        String l = line.trim();
        if (l.isEmpty()) continue;

        Matcher m = APPENDIX_PATTERN.matcher(l);
        if (m.matches()) {
          int page = Integer.parseInt(m.group(2));
          if (page < min) min = page;
          found = true;
        }
      }

      return found ? OptionalInt.of(min) : OptionalInt.empty();
    }

    if (style != TocStyle.NUMBERED) {
      return OptionalInt.empty();
    }

    // Pass 1: find the maximum top-level chapter start page (from "N Title Page" lines)
    int maxChapterStart = -1;
    boolean sawNumberedChapter = false;

    for (String line : tocPages.split("\\R")) {
      String l = line.trim();
      if (l.isEmpty()) continue;

      Matcher m = NUMBERED_HEADING_PATTERN.matcher(l);
      if (m.matches()) {
        sawNumberedChapter = true;
        int start = Integer.parseInt(m.group(3));
        if (start > maxChapterStart) maxChapterStart = start;
      }
    }

    if (!sawNumberedChapter || maxChapterStart < 0) {
      return OptionalInt.empty();
    }

    // Pass 2: find the first "non-digit title + page" that occurs AFTER maxChapterStart
    int minBoundary = Integer.MAX_VALUE;
    boolean foundBoundary = false;

    for (String line : tocPages.split("\\R")) {
      String l = line.trim();
      if (l.isEmpty()) continue;

      Matcher m = TITLE_WITH_PAGE_PATTERN.matcher(l);
      if (m.matches()) {
        int page = Integer.parseInt(m.group(2));

        // critical filter: must be after the last real chapter start
        if (page > maxChapterStart) {
          if (page < minBoundary) minBoundary = page;
          foundBoundary = true;
        }
      }
    }

    return foundBoundary ? OptionalInt.of(minBoundary) : OptionalInt.empty();
  }

  private static String cleanTitle(String title) {
    return title.replaceAll("\\s*\\.{2,}\\s*$", "").trim();
  }
}
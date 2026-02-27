package nl.adgroot.pdfsummarizer.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nl.adgroot.pdfsummarizer.text.Chapter;

public class TableOfContentConverter {

  // "Chapter 36: Running Simple Node.js Application 131"
  private static final Pattern CHAPTER_HEADING_PATTERN = Pattern.compile(
      "^\\s*(Chapter\\s+\\d+:\\s+.*?)(?:\\s+(\\d+))\\s*$",
      Pattern.CASE_INSENSITIVE
  );

  // "4 Some Title 50"
  // group(1)=chapterNr, group(2)=title (can contain numbers), group(3)=pageNr
  private static final Pattern NUMBERED_HEADING_PATTERN = Pattern.compile(
      "^\\s*(\\d+)\\s+(.+?)\\s+(\\d+)\\s*$"
  );

  public static List<Chapter> convert(String tocPages) {
    if (tocPages == null || tocPages.isBlank()) {
      return List.of();
    }

    // Split at either:
    // - "Chapter N:" (any case)
    // - "N <title> <page>" (start of line)
    String[] chunks = tocPages.split(
        "(?im)(?=^\\s*(?:chapter\\s+\\d+:|\\d+\\s+.+?\\s+\\d+\\s*$))"
    );

    List<Chapter> chapters = new ArrayList<>();

    for (String chunk : chunks) {
      if (chunk == null || chunk.isBlank()) continue;

      String[] lines = chunk.split("\\R");
      if (lines.length == 0) continue;

      String firstLine = lines[0].trim();
      if (firstLine.isEmpty()) continue;

      Matcher m = CHAPTER_HEADING_PATTERN.matcher(firstLine);
      if (m.matches()) {
        String title = cleanTitle(m.group(1).trim());
        int start = Integer.parseInt(m.group(2));

        Chapter chapter = new Chapter(title);
        chapter.start = start;
        chapters.add(chapter);
        continue;
      }

      m = NUMBERED_HEADING_PATTERN.matcher(firstLine);
      if (m.matches()) {
        int chapterNr = Integer.parseInt(m.group(1));
        String chapterTitle = cleanTitle(m.group(2).trim());
        int start = Integer.parseInt(m.group(3)); // <-- IMPORTANT: page is group(3)

        Chapter chapter = new Chapter("Chapter " + chapterNr + ": " + chapterTitle);
        chapter.start = start;
        chapters.add(chapter);
      }
    }

    if (chapters.isEmpty()) {
      return List.of();
    }

    for (int i = 0; i < chapters.size(); i++) {
      Chapter current = chapters.get(i);

      if (i < chapters.size() - 1) {
        int nextStart = chapters.get(i + 1).start;
        current.end = nextStart - 1;
      } else {
        // leave last chapter end as 0 for now
      }
    }

    return chapters;
  }

  private static String cleanTitle(String title) {
    return title
        .replaceAll("\\.{2,}\\s*$", "")  // remove trailing "....."
        .trim();
  }
}
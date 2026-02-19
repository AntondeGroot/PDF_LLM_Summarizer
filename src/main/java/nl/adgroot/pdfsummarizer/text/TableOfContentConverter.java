package nl.adgroot.pdfsummarizer.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TableOfContentConverter {

  public static List<Chapter> convert(String tocPages) {
    if (tocPages == null || tocPages.isBlank()) {
      return List.of();
    }

    // Split into chunks starting at each "Chapter N:"
    String[] chunks = tocPages.split("(?=\\bChapter\\s+\\d+:)");

    // Match chapter heading line and capture:
    // 1) the title part (without trailing page number)
    // 2) the start page number at the end of the line
    Pattern headingPattern = Pattern.compile("^(Chapter\\s+\\d+:\\s+.*?)(?:\\s+(\\d+))\\s*$");

    List<Chapter> chapters = new ArrayList<>();

    for (String chunk : chunks) {
      if (chunk == null || chunk.isBlank()) continue;

      String[] lines = chunk.split("\\R");
      if (lines.length == 0) continue;

      String firstLine = lines[0].trim();
      if (firstLine.isEmpty()) continue;

      Matcher m = headingPattern.matcher(firstLine);
      if (!m.matches()) continue;

      String title = m.group(1).trim();          // "Chapter 36: Running Simple Node.js Application"
      int start = Integer.parseInt(m.group(2));  // 131

      Chapter chapter = new Chapter(title);
      chapter.start = start;
      chapters.add(chapter);
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
        // the end of the last chapter can for now not be determined.
        // So instead just leave it at 0, then remove the TOC pages from the other pages
        // then set the end of the last chapter as the last page number
      }
    }

    return chapters;
  }
}

package nl.adgroot.pdfsummarizer.pdf.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nl.adgroot.pdfsummarizer.AppLogger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public class PdfPreviewComposer {

  private static final String DEFAULT_FONT_RESOURCE = "/fonts/JetBrains/JetBrainsMono-Regular.ttf";
  private static final AppLogger log = AppLogger.getLogger(PdfPreviewComposer.class);

  public void composeOriginalPlusTextPages(
      List<PdfObject> pages,
      Path outputPdf
  ) throws IOException {

    try (PDDocument out = new PDDocument()) {

      PDFont font = loadFont(out, DEFAULT_FONT_RESOURCE);

      for (int i = 0; i < pages.size(); i++) {
        PdfObject p = pages.get(i);

        // Use original page size as template (but don't import it)
        PDDocument single = p.getDocument();
        if (single == null || single.getNumberOfPages() == 0) continue;

        PDRectangle mediaBox = single.getPage(0).getMediaBox();

        // 1) TEXT PAGE (extracted from PDF)
        String textContent = "[PDF p." + p.getOriginalPageNr() + "] " + p.getChapter() + "\n"
            + (p.getTextReadFromPdf() != null ? p.getTextReadFromPdf() : "");
        log.debug(debugNonAscii("ABOUT TO WRITE (PDF TEXT): " + textContent));
        writeWrappedText(out, mediaBox, textContent, font);

        // 2) NOTES PAGE(S) (LLM notes — may overflow onto multiple pages)
        if (!p.hasNotes()) {
          log.info("no notes were found for page:" + p.getOriginalPageNr());
        }
        String notes = p.hasNotes()
            ? p.getNotes()
            : "The LLM could not determine any Q&A cards for this page.";
        String notesContent = "[PDF p." + p.getOriginalPageNr() + "] " + p.getChapter() + "\n" + notes;
        log.debug(debugNonAscii("ABOUT TO WRITE (NOTES): " + notesContent));
        writeWrappedText(out, mediaBox, notesContent, font);
      }

      out.save(outputPdf.toFile());
    }
  }

  private static String debugNonAscii(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 32 || c > 126) sb.append(String.format("\\u%04X", (int) c));
      else sb.append(c);
    }
    return sb.toString();
  }

  private static PDFont loadFont(PDDocument doc, String classpathResource) throws IOException {
    try (InputStream is = PdfPreviewComposer.class.getResourceAsStream(classpathResource)) {
      if (is == null) {
        throw new IllegalStateException("Font not found on classpath: " + classpathResource);
      }
      return PDType0Font.load(doc, is);
    }
  }

  private void writeWrappedText(PDDocument doc, PDRectangle mediaBox, String text, PDFont font) throws IOException {
    float margin = 48f;
    float fontSize = 10f;
    float leading = 1.2f * fontSize;
    float maxWidth = mediaBox.getWidth() - 2 * margin;
    int linesPerPage = Math.max(1, (int) ((mediaBox.getHeight() - 2 * margin) / leading));

    String safe = (text == null) ? "" : text.replace("\r", "");
    List<String> allLines = wrapPreserveSpaces(safe, font, fontSize, maxWidth);

    int offset = 0;
    do {
      int end = Math.min(offset + linesPerPage, allLines.size());
      List<String> pageLines = allLines.subList(offset, end);
      offset = end;

      PDPage page = new PDPage(mediaBox);
      doc.addPage(page);

      try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.OVERWRITE, true, true)) {
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.newLineAtOffset(margin, mediaBox.getHeight() - margin);
        for (String line : pageLines) {
          cs.showText(line);
          cs.newLineAtOffset(0, -leading);
        }
        cs.endText();
      }
    } while (offset < allLines.size());
  }

  private static List<String> wrapPreserveSpaces(String text, PDFont font, float fontSize, float maxWidth)
      throws IOException {

    List<String> lines = new ArrayList<>();

    for (String paragraph : text.split("\n", -1)) {
      if (paragraph.isEmpty()) {
        lines.add("");
        continue;
      }

      List<String> tokens = new ArrayList<>();
      StringBuilder tok = new StringBuilder();
      Boolean inWs = null;

      for (int i = 0; i < paragraph.length(); i++) {
        char c = paragraph.charAt(i);
        boolean ws = (c == ' ' || c == '\t');
        if (inWs == null || ws == inWs) {
          tok.append(c);
        } else {
          tokens.add(tok.toString());
          tok.setLength(0);
          tok.append(c);
        }
        inWs = ws;
      }
      if (tok.length() > 0) tokens.add(tok.toString());

      StringBuilder line = new StringBuilder();
      for (String t : tokens) {
        String candidate = line.toString() + t;
        float w = font.getStringWidth(candidate) / 1000f * fontSize;

        if (w <= maxWidth || line.length() == 0) {
          line.setLength(0);
          line.append(candidate);
        } else {
          lines.add(rstrip(line.toString()));
          line.setLength(0);

          if (font.getStringWidth(t) / 1000f * fontSize > maxWidth) {
            for (String chunk : hardWrap(t, font, fontSize, maxWidth)) {
              lines.add(rstrip(chunk));
            }
          } else {
            line.append(t);
          }
        }
      }
      lines.add(rstrip(line.toString()));
    }

    return lines;
  }

  private static List<String> hardWrap(String token, PDFont font, float fontSize, float maxWidth) throws IOException {
    List<String> out = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      String candidate = line.toString() + c;
      float w = font.getStringWidth(candidate) / 1000f * fontSize;
      if (w <= maxWidth || line.isEmpty()) {
        line.append(c);
      } else {
        out.add(line.toString());
        line.setLength(0);
        line.append(c);
      }
    }
    if (!line.isEmpty()) out.add(line.toString());
    return out;
  }

  private static String rstrip(String s) {
    int end = s.length();
    while (end > 0) {
      char c = s.charAt(end - 1);
      if (c == ' ' || c == '\t') end--;
      else break;
    }
    return s.substring(0, end);
  }
}
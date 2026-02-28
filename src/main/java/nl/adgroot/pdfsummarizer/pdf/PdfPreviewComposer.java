package nl.adgroot.pdfsummarizer.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

public class PdfPreviewComposer {

  private static final String DEFAULT_FONT_RESOURCE = "/fonts/JetBrains/JetBrainsMono-Regular.ttf";

  public void composeOriginalPlusTextPages(
      List<PdfObject> pages,
      Path outputPdf
  ) throws IOException {

    try (PDDocument out = new PDDocument()) {

      PDFont font = loadFont(out, DEFAULT_FONT_RESOURCE);

      for (int i = 0; i < pages.size(); i++) {
        PdfObject p = pages.get(i);

        PDDocument single = p.getDocument();
        if (single == null || single.getNumberOfPages() == 0) continue;

        // 1) original page
        PDPage original = single.getPage(0);
        out.importPage(original);

        // 2) notes page (from PdfObject itself)
        if (p.hasNotes()) {
          PDRectangle mediaBox = original.getMediaBox();
          PDPage textPage = new PDPage(mediaBox);
          out.addPage(textPage);

          String s = p.getNotes();

          // keep your debug prefix behavior (unchanged)
          s = p.getIndex() + p.getChapter() + s;
          System.out.println(debugNonAscii("ABOUT TO WRITE: " + s));

          writeWrappedText(out, textPage, s, font);
        }
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

  private void writeWrappedText(PDDocument doc, PDPage page, String text, PDFont font) throws IOException {
    float margin = 48f;
    float fontSize = 10f;
    float leading = 1.2f * fontSize;

    PDRectangle box = page.getMediaBox();
    float maxWidth = box.getWidth() - 2 * margin;

    float x = margin;
    float y = box.getHeight() - margin;

    String safe = (text == null) ? "" : text.replace("\r", "");

    try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.OVERWRITE, true, true)) {
      cs.beginText();
      cs.setFont(font, fontSize);
      cs.newLineAtOffset(x, y);

      for (String line : wrapPreserveSpaces(safe, font, fontSize, maxWidth)) {
        if (y < margin) break;

        cs.showText(line);
        cs.newLineAtOffset(0, -leading);
        y -= leading;
      }

      cs.endText();
    }
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
      if (w <= maxWidth || line.length() == 0) {
        line.append(c);
      } else {
        out.add(line.toString());
        line.setLength(0);
        line.append(c);
      }
    }
    if (line.length() > 0) out.add(line.toString());
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
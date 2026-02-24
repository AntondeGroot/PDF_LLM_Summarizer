package nl.adgroot.pdfsummarizer.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

public class PdfPreviewComposer {

  /**
   * Creates a single PDF that contains, for each i:
   *  - the original page i (from pdfPages)
   *  - a generated page containing parsedTextPages.get(i)
   *
   * Caller owns pdfPages and should close them elsewhere.
   */
  public void composeOriginalPlusTextPages(
      List<PDDocument> pdfPages,
      List<String> parsedTextPages,
      Path outputPdf
  ) throws IOException {

    int n = Math.min(pdfPages.size(), parsedTextPages.size());

    try (PDDocument out = new PDDocument()) {

      for (int i = 0; i < n; i++) {

        // 1) add original page (import into output doc)
        PDPage original = pdfPages.get(i).getPage(0);
        out.importPage(original);

        // 2) add generated text page (same size as original)
        PDRectangle mediaBox = original.getMediaBox();
        PDPage textPage = new PDPage(mediaBox);
        out.addPage(textPage);

        writeWrappedText(out, textPage, parsedTextPages.get(i));
      }

      out.save(outputPdf.toFile());
    }
  }

  private void writeWrappedText(PDDocument doc, PDPage page, String text) throws IOException {
    float margin = 48f;
    float fontSize = 10f;
    float leading = 1.2f * fontSize;

    var font = new PDType1Font(FontName.HELVETICA);

    PDRectangle box = page.getMediaBox();
    float width = box.getWidth() - 2 * margin;
    float startX = margin;
    float startY = box.getHeight() - margin;

    try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.OVERWRITE, true, true)) {
      cs.beginText();
      cs.setFont(font, fontSize);
      cs.newLineAtOffset(startX, startY);

      for (String line : wrap(text == null ? "" : text, font, fontSize, width)) {
        cs.showText(line);
        cs.newLineAtOffset(0, -leading);

        // stop if we run off the page (simple approach)
        if ((startY -= leading) < margin) {
          break;
        }
      }

      cs.endText();
    }
  }

  /**
   * Very simple word-wrap. Good enough for preview.
   * (If you want perfect layout, you’ll end up implementing more logic.)
   */
  private static List<String> wrap(String text,
      org.apache.pdfbox.pdmodel.font.PDFont font,
      float fontSize,
      float maxWidth) throws IOException {

    java.util.List<String> lines = new java.util.ArrayList<>();
    for (String paragraph : text.replace("\r", "").split("\n")) {

      StringBuilder line = new StringBuilder();
      for (String word : paragraph.split("\\s+")) {
        if (word.isBlank()) continue;

        String candidate = line.isEmpty() ? word : line + " " + word;
        float w = font.getStringWidth(candidate) / 1000f * fontSize;

        if (w <= maxWidth) {
          line.setLength(0);
          line.append(candidate);
        } else {
          if (!line.isEmpty()) lines.add(line.toString());
          line.setLength(0);
          line.append(word);
        }
      }

      if (!line.isEmpty()) lines.add(line.toString());
      // preserve paragraph breaks with an empty line
      lines.add("");
    }
    return lines;
  }
}
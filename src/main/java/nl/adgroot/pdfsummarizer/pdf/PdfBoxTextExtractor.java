package nl.adgroot.pdfsummarizer.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class PdfBoxTextExtractor {
  public String extract(Path pdfPath) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      return stripper.getText(doc);
    }
  }

  public List<String> extractPages(Path pdfPath) throws IOException {

    try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      List<String> pages = new ArrayList<>();

      for (int i = 1; i <= doc.getNumberOfPages(); i++) {
        stripper.setStartPage(i);
        stripper.setEndPage(i);
        pages.add(stripper.getText(doc));
      }

      return pages;
    }
  }
}

package nl.adgroot.pdfsummarizer.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;

public class PdfBoxPdfSplitter {

  /**
   * Splits a PDF into one in-memory PDDocument per page.
   *
   * <p><b>Important:</b> The returned PDDocument instances must be closed by the caller.</p>
   */
  public List<PDDocument> splitInMemory(Path pdfPath) throws IOException {

    // Load the source PDF
    try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {

      Splitter splitter = new Splitter();
      splitter.setSplitAtPage(1); // one page per document

      // Splitter returns separate PDDocument instances (one per page)
      return splitter.split(document);
    }
  }
}
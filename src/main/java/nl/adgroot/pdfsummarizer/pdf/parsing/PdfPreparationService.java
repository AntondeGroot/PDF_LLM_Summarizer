package nl.adgroot.pdfsummarizer.pdf.parsing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.text.Page;
import org.apache.pdfbox.pdmodel.PDDocument;

public class PdfPreparationService {

  private final PdfBoxTextExtractor extractor;
  private final PdfBoxPdfSplitter pdfSplitter;
  private final PreviewSelectionService selector = new PreviewSelectionService();

  public PdfPreparationService(PdfBoxTextExtractor extractor, PdfBoxPdfSplitter pdfSplitter) {
    this.extractor = extractor;
    this.pdfSplitter = pdfSplitter;
  }

  public PreparedPdf loadAndPrepare(Path pdfPath, AppConfig cfg) throws IOException {
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    List<PDDocument> pdfPagesAll = pdfSplitter.splitInMemory(pdfPath);

    ParsedPDF parsedPdf = new ParsedPDF(pagesWithTOC, cfg.cards.nrOfLinesUsedForContext);

    // Build PdfObjects using the exact original-PDF index stored on each Page.
    // This handles leading pages, trailing pages, and inter-chapter gaps correctly.
    List<Page> fullContent = parsedPdf.getContent();
    int total = fullContent.size();

    List<PdfObject> allObjects = new ArrayList<>(total);
    for (int i = 0; i < total; i++) {
      Page p = fullContent.get(i);
      int origIdx = p.getOriginalPageIndex();

      if (origIdx < 0 || origIdx >= pdfPagesAll.size()) {
        // Safety: original index was not set or is out of range — clamp here
        parsedPdf.setContent(new ArrayList<>(fullContent.subList(0, i)));
        total = i;
        break;
      }

      PDDocument doc = pdfPagesAll.get(origIdx);
      int originalPageNr = origIdx + 1; // 1-based page number in the original PDF
      allObjects.add(new PdfObject(i, originalPageNr, p.chapter, doc, p.toString()));
    }

    // Apply preview selection ONCE (indexes are content-indexes into the "allObjects" list)
    List<Integer> selectedIndexes = selector.selectIndexes(cfg, total);

    if (cfg != null && cfg.preview != null && cfg.preview.enabled) {
      // select PdfObjects
      List<PdfObject> selectedObjects = selector.selectByIndex(allObjects, selectedIndexes);

      // trim parsed content to match the same selection
      parsedPdf.setContent(selector.selectByIndex(parsedPdf.getContent(), selectedIndexes));

      return new PreparedPdf(parsedPdf, selectedObjects);
    }

    return new PreparedPdf(parsedPdf, allObjects);
  }

}
package nl.adgroot.pdfsummarizer.pdf.parsing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxPdfSplitter;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.text.Chapter;
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

    ParsedPDF parsedPdf = new ParsedPDF(pagesWithTOC);
    List<Chapter> tableOfContent = parsedPdf.getTableOfContent();
    List<String> strippedPages = parsedPdf.getStrippedPages();
    int contentStartIndex = parsedPdf.getContentStartIndex();
    int offset = -tableOfContent.getFirst().start;

    int chapterIdx = 0;
    Chapter currentChapter = tableOfContent.getFirst();
    List<PdfObject> allObjects = new ArrayList<>();

    for (int i = 0; i < strippedPages.size(); i++) {
      int pdfPageNr = i + 1;

      while (chapterIdx + 1 < tableOfContent.size()) {
        int nextPdfStart = tableOfContent.get(chapterIdx + 1).start + offset;
        if (pdfPageNr >= nextPdfStart) {
          chapterIdx++;
          currentChapter = tableOfContent.get(chapterIdx);
        } else {
          break;
        }
      }

      int currentPdfStart = currentChapter.start + offset;
      int currentPdfEnd   = currentChapter.end + offset;

      if (pdfPageNr >= currentPdfStart && pdfPageNr <= currentPdfEnd) {
        int origIdx = contentStartIndex + i;
        if (origIdx < 0 || origIdx >= pdfPagesAll.size()) {
          break;
        }
        PDDocument doc = pdfPagesAll.get(origIdx);
        int originalPageNr = origIdx + 1;
        allObjects.add(new PdfObject(allObjects.size(), originalPageNr, currentChapter.header, doc, strippedPages.get(i)));
      }
    }

    int total = allObjects.size();
    List<Integer> selectedIndexes = selector.selectIndexes(cfg, total);

    if (cfg != null && cfg.preview != null && cfg.preview.enabled) {
      return new PreparedPdf(tableOfContent, selector.selectByIndex(allObjects, selectedIndexes));
    }

    return new PreparedPdf(tableOfContent, allObjects);
  }
}
package nl.adgroot.pdfsummarizer.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import org.apache.pdfbox.pdmodel.PDDocument;

public class PdfPreparationService {

  private final PdfBoxTextExtractor extractor;
  private final PdfBoxPdfSplitter pdfSplitter;

  public PdfPreparationService(PdfBoxTextExtractor extractor, PdfBoxPdfSplitter pdfSplitter) {
    this.extractor = extractor;
    this.pdfSplitter = pdfSplitter;
  }

  public PreparedPdf loadAndPrepare(Path pdfPath, AppConfig cfg) throws IOException {
    // read PDF
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    List<PDDocument> pdfPages = pdfSplitter.splitInMemory(pdfPath);

    ParsedPDF parsedPdf = new ParsedPDF(pagesWithTOC, cfg.cards.nrOfLinesUsedForContext);
    pdfPages = alignPdfPagesToParsedContent(pdfPages, parsedPdf);
    pdfPages = applyPreviewIfEnabled(cfg, parsedPdf, pdfPages);

    return new PreparedPdf(parsedPdf, pdfPages);
  }

  /**
   * Align PDDocument pages to ParsedPDF content pages.
   *
   */
  private static List<PDDocument> alignPdfPagesToParsedContent(List<PDDocument> pdfPages,
      ParsedPDF parsedPdf) {
    int contentSize = parsedPdf.getContent().size();
    int tocStart = parsedPdf.getTableOfContent().getFirst().start;

    pdfPages = pdfPages.subList(
        pdfPages.size() - contentSize - tocStart,
        pdfPages.size()
    );
    pdfPages = pdfPages.subList(0, contentSize);
    return pdfPages;
  }

  private List<PDDocument> applyPreviewIfEnabled(
      AppConfig cfg,
      ParsedPDF parsedPdf,
      List<PDDocument> pdfPages
  ) {
    PreviewSelectionService selector = new PreviewSelectionService();

    int total = parsedPdf.getContent().size();
    List<Integer> selectedIndexes = selector.selectIndexes(cfg, total);

    // If preview disabled, selectedIndexes == [0..total-1] and nothing changes.
    if (cfg == null || cfg.preview == null || !cfg.preview.enabled) {
      return pdfPages;
    }

    System.out.println("selected indexes for preview are: " + selectedIndexes);
    System.out.println("pdfpages size: " + pdfPages.size()
        + ", parsedPdf size: " + parsedPdf.getContent().size());

    List<PDDocument> selectedPdfPages = selector.selectByIndex(pdfPages, selectedIndexes);
    parsedPdf.setContent(selector.selectByIndex(parsedPdf.getContent(), selectedIndexes));

    System.out.println("Preview mode: using pages " + selectedIndexes);

    return selectedPdfPages;
  }
}
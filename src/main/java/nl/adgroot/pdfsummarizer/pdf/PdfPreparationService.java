package nl.adgroot.pdfsummarizer.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
   * */
  private static List<PDDocument> alignPdfPagesToParsedContent(List<PDDocument> pdfPages, ParsedPDF parsedPdf) {
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
    if (!cfg.preview.enabled) {
      return pdfPages;
    }

    int total = parsedPdf.getContent().size();
    int n = Math.min(cfg.preview.nrPages, total);

    List<Integer> selectedIndexes = cfg.preview.randomPages
        ? randomIndexes(total, n)
        : firstIndexes(n);

    System.out.println("selected indexes for preview are: " + selectedIndexes);
    System.out.println("pdfpages size: " + pdfPages.size()
        + ", parsedPdf size: " + parsedPdf.getContent().size());

    List<PDDocument> selectedPdfPages = selectByIndex(pdfPages, selectedIndexes);
    parsedPdf.setContent(selectByIndex(parsedPdf.getContent(), selectedIndexes));

    System.out.println("Preview mode: using pages " + selectedIndexes);

    return selectedPdfPages;
  }

  // ---------- preview helpers ----------

  private static List<Integer> firstIndexes(int n) {
    List<Integer> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      list.add(i);
    }
    return list;
  }

  private static List<Integer> randomIndexes(int total, int n) {
    List<Integer> all = new ArrayList<>(total);
    for (int i = 0; i < total; i++) {
      all.add(i);
    }
    java.util.Collections.shuffle(all);
    return all.subList(0, n).stream().sorted().toList();
  }

  private static <T> List<T> selectByIndex(List<T> source, List<Integer> indexes) {
    return indexes.stream()
        .sorted()
        .map(source::get)
        .toList();
  }
}
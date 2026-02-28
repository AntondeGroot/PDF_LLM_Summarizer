package nl.adgroot.pdfsummarizer.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nl.adgroot.pdfsummarizer.config.AppConfig;
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

    // Align pdfPagesAll to parsedPdf.getContent()
    List<PDDocument> alignedPdfPages = alignPdfPagesToParsedContent(pdfPagesAll, pagesWithTOC, parsedPdf);

    // Build PdfObjects 1:1 from FULL content (before preview selection)
    List<Page> fullContent = parsedPdf.getContent();
    int total = fullContent.size();

    // Defensive: if alignment produced fewer docs than content, clamp to min
    int n = Math.min(total, alignedPdfPages.size());

    List<PdfObject> allObjects = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Page p = fullContent.get(i);
      PDDocument doc = alignedPdfPages.get(i);

      String chapter = p.chapter;
      String text = p.toString();

      // IMPORTANT: index is now the ORIGINAL content index (stable identity)
      allObjects.add(new PdfObject(i, chapter, doc, text));
    }

    // If alignment forced clamping, keep ParsedPDF content consistent with objects
    if (n != total) {
      parsedPdf.setContent(new ArrayList<>(fullContent.subList(0, n)));
      total = n;
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

  private static List<PDDocument> alignPdfPagesToParsedContent(
      List<PDDocument> pdfPages,
      List<String> pagesWithTOC,
      ParsedPDF parsedPdf
  ) {
    int contentSize = parsedPdf.getContent().size();
    if (contentSize <= 0) return List.of();

    // Anchor alignment by first actual content page text (avoids TOC collisions)
    String firstContentText = String.valueOf(parsedPdf.getContent().getFirst());
    String needle = makeNeedle(firstContentText);

    int firstContentIndex = findFirstPageContaining(pagesWithTOC, needle);

    if (firstContentIndex < 0) {
      int start = Math.max(0, pdfPages.size() - contentSize);
      return pdfPages.subList(start, pdfPages.size());
    }

    int endExclusive = Math.min(firstContentIndex + contentSize, pdfPages.size());
    return pdfPages.subList(firstContentIndex, endExclusive);
  }

  private static String makeNeedle(String s) {
    if (s == null) return "";
    String compact = s.replace("\r", "").trim().replaceAll("\\s+", " ");
    int len = Math.min(80, compact.length());
    return compact.substring(0, len);
  }

  private static int findFirstPageContaining(List<String> pages, String needle) {
    if (pages == null || pages.isEmpty()) return -1;
    if (needle == null || needle.isBlank()) return -1;

    for (int i = 0; i < pages.size(); i++) {
      String p = pages.get(i);
      if (p == null) continue;
      String compact = p.replace("\r", "").trim().replaceAll("\\s+", " ");
      if (compact.contains(needle)) return i;
    }
    return -1;
  }
}
package nl.adgroot.pdfsummarizer.pdf;

import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Result of loading + preparing a PDF for processing.
 * - parsed: parsed text content + TOC
 * - pdfPages: PDDocument pages aligned with parsed.getContent()
 */
public record PreparedPdf(
    ParsedPDF parsed,
    List<PDDocument> pdfPages
) {}
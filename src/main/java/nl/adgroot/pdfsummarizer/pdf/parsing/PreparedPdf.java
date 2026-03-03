package nl.adgroot.pdfsummarizer.pdf.parsing;

import java.util.List;

/**
 * Result of loading + preparing a PDF for processing.
 * - parsed: parsed text content + TOC
 * - pdfPages: PDDocument pages aligned with parsed.getContent()
 */
public record PreparedPdf(
    ParsedPDF parsed,
    List<PdfObject> pdfPages
) {}
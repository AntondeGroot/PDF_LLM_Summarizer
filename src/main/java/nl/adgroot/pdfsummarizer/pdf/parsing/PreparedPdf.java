package nl.adgroot.pdfsummarizer.pdf.parsing;

import java.util.List;

public record PreparedPdf(
    List<Chapter> tableOfContent,
    List<PdfObject> pdfPages
) {}
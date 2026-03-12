package nl.adgroot.pdfsummarizer.pdf.parsing;

import java.util.List;
import nl.adgroot.pdfsummarizer.text.Chapter;

public record PreparedPdf(
    List<Chapter> tableOfContent,
    List<PdfObject> pdfPages
) {}
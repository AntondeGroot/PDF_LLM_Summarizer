package nl.adgroot.pdfsummarizer.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import org.junit.jupiter.api.Test;

class GlyphsInPDFTest {

  @Test
  void readGlyphsInPdf() throws URISyntaxException, IOException {
    String expectedString = """
        Chapter 11: Docker events
        Examples
        Launch a container and be notified of related events
        The documentation for docker events provides details,""";

    // GIVEN
    String pdfName = "Learning Docker-57.pdf";
    Path pdfPath = Paths.get(Objects.requireNonNull(GlyphsInPDFTest.class.getClassLoader().getResource(pdfName)).toURI());

    // WHEN you extract the pdf page containing Glyphs "docker events"
    // is as you can see in the pdf in a different 'font'
    // when you save the page it will display "***********"
    // this test is to make sure the text could be read and processed by the LLM
    PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();
    List<String> pages = extractor.extractPages(pdfPath);

    // THEN
    assertEquals(expectedString, pages.getFirst().substring(0,141));
  }
}

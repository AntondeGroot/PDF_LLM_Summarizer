package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class ParsedPdfIntegrationTest {

  static Path pdfPath;

  PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();

  @BeforeAll
  static void setup() throws URISyntaxException {
    pdfPath = getPdfPath();
  }

  @Test
  void firstChapter_InTableOfContents_HasCorrectTitle() throws Exception{
    // GIVEN
    List<String> pages = extractor.extractPages(pdfPath);

    // WHEN
    ParsedPDF parsed = new ParsedPDF(pages, 0);

    // THEN
    assertFalse(parsed.getTableOfContent().isEmpty(), "Expected a Table Of Content");
    assertEquals("Chapter 1: Getting started", parsed.getTableOfContent().getFirst().header,
        "Expected first chapter title of ParsedPDF");
  }

  @Test
  void lastChapter_InTableOfContents_HasCorrectTitle() throws Exception{
    // GIVEN
    List<String> pages = extractor.extractPages(pdfPath);

    // WHEN
    ParsedPDF parsed = new ParsedPDF(pages, 0);

    // THEN
    assertFalse(parsed.getTableOfContent().isEmpty(), "Expected a Table Of Content");
    assertEquals("Chapter 11: Troubleshooting", parsed.getTableOfContent().getLast().header,
        "Expected first chapter title of ParsedPDF");
  }

  @Disabled
  @Test
  void lastChapter_InTableOfContents_HasCorrectRange() throws Exception{
    // GIVEN
    List<String> pages = extractor.extractPages(pdfPath);

    // WHEN
    ParsedPDF parsed = new ParsedPDF(pages, 0);

    // THEN
    assertFalse(parsed.getTableOfContent().isEmpty(), "Expected a Table Of Content");
    assertEquals(24 , parsed.getTableOfContent().getLast().start,
        "Expected first chapter title of ParsedPDF");
    assertEquals(26, parsed.getTableOfContent().getLast().end,
        "Expected first chapter title of ParsedPDF");
  }

  @Test
  void ParsedContentPage_StartsOnFirstChapterPage() throws Exception {
    // GIVEN
    List<String> pages = extractor.extractPages(pdfPath);

    // WHEN
    ParsedPDF parsed = new ParsedPDF(pages, 0);

    // THEN
    assertFalse(parsed.getContent().isEmpty(), "Expected content pages");
    assertTrue(parsed.getContent().getFirst().content.contains("Chapter 1: Getting started"),
        "Expected the first page to start on the first chapter");
  }

  @Disabled
  @Test
  void ParsedContentPage_EndsOnLastChapterPage() throws Exception {
    // GIVEN
    List<String> pages = extractor.extractPages(pdfPath);

    // WHEN
    ParsedPDF parsed = new ParsedPDF(pages, 0);

    // THEN
    assertFalse(parsed.getContent().isEmpty(), "Expected content pages");
    assertTrue(parsed.getContent().getLast().content.contains("11.2 Runtime issues"),//todo: add a unique string to last page
        "Expected the last page to end on the last chapter: 11.2 but it was: "+parsed.getContent().getLast().content);
  }

  private static Path getPdfPath() throws URISyntaxException {
    return Paths.get(
        Objects.requireNonNull(ParsedPdfIntegrationTest.class.getClassLoader().getResource(
            "LaTeX_pdffile_realistic.pdf")).toURI()
    );
  }
}
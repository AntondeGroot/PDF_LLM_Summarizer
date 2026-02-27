package nl.adgroot.pdfsummarizer.text;

import static nl.adgroot.pdfsummarizer.pdf.TableOfContentConverter.convert;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import org.junit.jupiter.api.Test;

class TableOfContentConverterTest {

  PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();
  String rawTOC =
    """
    Chapter 35: Running services 130
    Examples 130
    Creating a more advanced service 130
    Creating a simple service 130
    Removing a service 130
    Scaling a service 130
    Chapter 36: Running Simple Node.js Application 131
    Examples 131
    Running a Basic Node.js application inside a Container 131
    Build your image 132
    Running the image 133
    Chapter 37: security 135
    Introduction 135
    Examples 135
    How to find from which image our image comes from 135
    Credits 136""";

  @Test
  void readTocWithoutReadingSubheadings() {
    List<Chapter> expectedTOC = List.of(
        new Chapter("Chapter 35: Running services", 130, 130),
        new Chapter("Chapter 36: Running Simple Node.js Application", 131, 134),
        new Chapter("Chapter 37: security", 135, 0)
    );

    assertEquals(expectedTOC, convert(rawTOC));
  }

  @Test
  void readTocWithStringContents() throws IOException, URISyntaxException {
    // GIVEN
    Path pdfPath =  getResourcePath("pdf_tableOfContents1.pdf");

    // WHEN
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    ParsedPDF parsedPDF = new ParsedPDF(pagesWithTOC,0);

    // THEN
    List<Chapter> toc = parsedPDF.getTableOfContent();
    assertEquals(5, toc.size());
  }

  @Test
  void readTocWithStringTableOfContents() throws IOException, URISyntaxException {
    // GIVEN
    Path pdfPath =  getResourcePath("pdf_tableOfContents2.pdf");

    // WHEN
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    ParsedPDF parsedPDF = new ParsedPDF(pagesWithTOC,0);

    // THEN
    List<Chapter> toc = parsedPDF.getTableOfContent();
    assertEquals(5, toc.size());
  }

  @Test
  void readTocWithStringChapterCapital(){
    // WHEN Chapter with C capitalized
    List<Chapter> toc = convert("Chapter 1: test1 1\nChapter 2: test 2");

    // THEN
    assertEquals(2, toc.size());
  }

  @Test
  void readTocWithStringChapterLowercase(){
    // WHEN Chapter with C capitalized
    List<Chapter> toc = convert("chapter 1: test1 1\nchapter 2: test 2");

    // THEN
    assertEquals(2, toc.size());
  }

  @Test
  void readToc_ChapterNumber_ChapterTitle_PageNr(){
    // WHEN Chapter with C capitalized
    List<Chapter> toc = convert("4 subject 4 14\n5 subject 5 15");

    // THEN
    assertEquals(2, toc.size());
  }

  @Test
  void readGriffithsTableOfContents() throws URISyntaxException, IOException {
    // when the chapters are e.g. : 4 ChapterTitle <<PageNumber>>
    Path pdfPath = getResourcePath("griffiths_4ed-5-13.pdf");

    // WHEN
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    ParsedPDF parsedPDF = new ParsedPDF(pagesWithTOC,0);

    // THEN number of chapters is correct with correct titles
    List<Chapter> toc = parsedPDF.getTableOfContent();
    assertEquals(12, toc.size());
    assertEquals("Chapter 1: Vector Analysis",toc.getFirst().header);// it added Chapter 1: to the header
    assertEquals("Chapter 12: Electrodynamics and Relativity", toc.getLast().header);
    // THEN start and end pages are correct
    assertEquals(1, toc.getFirst().start);
    assertEquals(58, toc.getFirst().end);
    assertEquals(502, toc.getLast().start);
    assertEquals(0, toc.getLast().end);
  }

  private Path getResourcePath(String fileName) throws URISyntaxException {
    return Paths.get(
        Objects.requireNonNull(TableOfContentConverterTest.class.getClassLoader().getResource(fileName)).toURI()
    );
  }

  @Test
  void readToc_dotLeaders_betweenTitleAndPage() {
    String toc =
        "Chapter 1: Intro .......... 1\n" +
            "Chapter 2: Setup ..... 5\n";

    List<Chapter> result = convert(toc);

    assertEquals(2, result.size());
    assertEquals("Chapter 1: Intro", result.getFirst().header);
    assertEquals("Chapter 2: Setup", result.getLast().header);
    assertEquals(1, result.get(0).start);
    assertEquals(4, result.get(0).end);
    assertEquals(5, result.get(1).start);
  }

  @Test
  void readToc_doesNotTreatNumberedSubheadingAsChapter_whenNotChapterLine() {
    String toc =
        "Chapter 1: Intro 1\n" +
            "1.1 Not a chapter 2\n" +
            "1.2 Also not a chapter 3\n" +
            "Chapter 2: Next 5\n";

    List<Chapter> result = convert(toc);

    assertEquals(2, result.size());
    assertEquals("Chapter 1: Intro", result.get(0).header);
    assertEquals("Chapter 2: Next", result.get(1).header);
  }

  @Test
  void readToc_numberedFormat_titleContainsManyNumbers() {
    String toc = "4 ISO 27001 2022 update 50\n5 Node.js 18 and 20 70\n";

    List<Chapter> result = convert(toc);

    assertEquals(2, result.size());
    assertEquals("Chapter 4: ISO 27001 2022 update", result.get(0).header);
    assertEquals(50, result.get(0).start);
    assertEquals(69, result.get(0).end);
    assertEquals("Chapter 5: Node.js 18 and 20", result.get(1).header);
    assertEquals(70, result.get(1).start);
  }

  @Test
  void convert_nullOrBlank_returnsEmptyList() {
    assertEquals(List.of(), convert(null));
    assertEquals(List.of(), convert(""));
    assertEquals(List.of(), convert("   \n\t"));
  }


}
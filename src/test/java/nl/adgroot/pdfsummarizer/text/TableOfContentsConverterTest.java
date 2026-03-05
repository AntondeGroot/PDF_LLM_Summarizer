package nl.adgroot.pdfsummarizer.text;

import static nl.adgroot.pdfsummarizer.pdf.tableOfContents.TableOfContentsConverter.convertTableOfContentsToChapterList;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import nl.adgroot.pdfsummarizer.pdf.parsing.ParsedPDF;
import nl.adgroot.pdfsummarizer.pdf.reader.PdfBoxTextExtractor;
import org.junit.jupiter.api.Test;

class TableOfContentsConverterTest {

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
        new Chapter("Chapter 37: security", 135, 136)
    );

    assertEquals(expectedTOC, convertTableOfContentsToChapterList(rawTOC, 136));
  }

  @Test
  void readTocWithStringContents() throws IOException, URISyntaxException {
    // GIVEN
    Path pdfPath = getResourcePath("pdf_tableOfContents1.pdf");

    // WHEN
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    ParsedPDF parsedPDF = new ParsedPDF(pagesWithTOC, 0);

    // THEN
    List<Chapter> toc = parsedPDF.getTableOfContent();
    assertEquals(5, toc.size());
  }

  @Test
  void readTocWithStringTableOfContents() throws IOException, URISyntaxException {
    // GIVEN
    Path pdfPath = getResourcePath("pdf_tableOfContents2.pdf");

    // WHEN
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    ParsedPDF parsedPDF = new ParsedPDF(pagesWithTOC, 0);

    // THEN
    List<Chapter> toc = parsedPDF.getTableOfContent();
    assertEquals(5, toc.size());
  }

  @Test
  void readTocWithStringChapterCapital() {
    // WHEN Chapter with C capitalized
    List<Chapter> toc = convertTableOfContentsToChapterList("Chapter 1: test1 1\nChapter 2: test 2", 136);

    // THEN
    assertEquals(2, toc.size());
  }

  @Test
  void readTocWithStringChapterLowercase() {
    // WHEN Chapter with c lowercase
    List<Chapter> toc = convertTableOfContentsToChapterList("chapter 1: test1 1\nchapter 2: test 2", 136);

    // THEN
    assertEquals(2, toc.size());
  }

  @Test
  void readToc_ChapterNumber_ChapterTitle_PageNr() {
    // WHEN numbered format: N <title> <page>
    List<Chapter> toc = convertTableOfContentsToChapterList("4 subject 4 14\n5 subject 5 15", 134);

    // THEN
    assertEquals(2, toc.size());
  }

  @Test
  void readGriffithsTableOfContents() throws URISyntaxException, IOException {
    // when the chapters are e.g. : 4 ChapterTitle <<PageNumber>>
    Path pdfPath = getResourcePath("griffiths_4ed-5-13.pdf");

    // WHEN
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    ParsedPDF parsedPDF = new ParsedPDF(pagesWithTOC, 0);

    // THEN number of chapters is correct with correct titles
    List<Chapter> toc = parsedPDF.getTableOfContent();
    assertEquals(12, toc.size());
    assertEquals("Vector Analysis", toc.getFirst().header);
    assertEquals("Electrodynamics and Relativity", toc.getLast().header);

    // THEN start and end pages are correct
    assertEquals(1, toc.getFirst().start);
    assertEquals(58, toc.getFirst().end);
    assertEquals(502, toc.getLast().start);
    assertEquals(574, toc.getLast().end);
  }

  private Path getResourcePath(String fileName) throws URISyntaxException {
    return Paths.get(
        Objects.requireNonNull(TableOfContentsConverterTest.class.getClassLoader().getResource(fileName)).toURI()
    );
  }

  @Test
  void readToc_dotLeaders_betweenTitleAndPage() {
    String toc =
        "Chapter 1: Intro .......... 1\n" +
            "Chapter 2: Setup ..... 5\n";

    List<Chapter> result = convertTableOfContentsToChapterList(toc, 7);

    assertEquals(2, result.size());
    assertEquals("Chapter 1: Intro", result.getFirst().header);
    assertEquals("Chapter 2: Setup", result.getLast().header);
    assertEquals(1, result.getFirst().start);
    assertEquals(4, result.getFirst().end);
    assertEquals(5, result.getLast().start);
    assertEquals(7, result.getLast().end);
  }

  @Test
  void readToc_doesNotTreatNumberedSubheadingAsChapter_whenNotChapterLine() {
    String toc =
        "Chapter 1: Intro 1\n" +
            "1.1 Not a chapter 2\n" +
            "1.2 Also not a chapter 3\n" +
            "Chapter 2: Next 5\n";

    List<Chapter> result = convertTableOfContentsToChapterList(toc, 136);

    assertEquals(2, result.size());
    assertEquals("Chapter 1: Intro", result.getFirst().header);
    assertEquals("Chapter 2: Next", result.get(1).header);
  }

  @Test
  void readToc_numberedFormat_titleContainsManyNumbers() {
    String toc = "4 ISO 27001 2022 update 50\n5 Node.js 18 and 20 70\n";

    List<Chapter> result = convertTableOfContentsToChapterList(toc, 136);

    assertEquals(2, result.size());
    assertEquals("ISO 27001 2022 update", result.getFirst().header);
    assertEquals(50, result.getFirst().start);
    assertEquals(69, result.getFirst().end);
    assertEquals("Node.js 18 and 20", result.getLast().header);
    assertEquals(70, result.getLast().start);
  }

  @Test
  void convert_nullOrBlank_returnsEmptyList() {
    assertEquals(List.of(), convertTableOfContentsToChapterList(null, 6));
    assertEquals(List.of(), convertTableOfContentsToChapterList("", 6));
    assertEquals(List.of(), convertTableOfContentsToChapterList("   \n\t", 6));
  }

  @Test
  void convert_TableOfContentsWith_subHeaders() {
    String toc = """
        11 Troubleshooting 24
        11.1 Build issues . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 24
        11.1.1 Symptom . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 24
        11.1.2 Resolution . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 24
        11.2 Runtime issues . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 25""";

    List<Chapter> tocChapters = convertTableOfContentsToChapterList(toc, 136);

    assertEquals(1, tocChapters.size());
    assertEquals("Troubleshooting", tocChapters.getFirst().header);
  }

  @Test
  void convert_LastChapterBeforeAppendices_CorrectEnd_AppendicesDontCount() {
    String toc = """
        11 Troubleshooting 24
        11.1 Build issues . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 24
        11.1.1 Symptom . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 24
        11.1.2 Resolution . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 24
        11.2 Runtime issues . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . 25
        Appendix A: Extra notes 27
        Appendix B: Glossary 28""";

    List<Chapter> tocChapters = convertTableOfContentsToChapterList(toc, 136);

    assertEquals(1, tocChapters.size());
    assertEquals(26, tocChapters.getLast().end);
  }

  @Test
  void numberedStyle_lastChapterEndsAtLastDocumentPage_whenNoAppendixBoundary() {
    String toc = """
        1 Intro 1
        2 Setup 10
        3 Usage 20
        """;

    List<Chapter> chapters = convertTableOfContentsToChapterList(toc, 42);

    assertEquals(3, chapters.size());
    assertEquals(1, chapters.getFirst().start);
    assertEquals(9, chapters.getFirst().end);
    assertEquals(10, chapters.get(1).start);
    assertEquals(19, chapters.get(1).end);
    assertEquals(20, chapters.get(2).start);
    assertEquals(42, chapters.get(2).end);
  }

  @Test
  void numberedStyle_switchesToLetteredAppendixEntries_capsLastChapterEnd() {
    String toc = """
        12 Electrodynamics and Relativity 502
        A Vector Calculus in Curvilinear Coordinates 575
        B The Helmholtz Theorem 582
        Index 589
        """;

    List<Chapter> chapters = convertTableOfContentsToChapterList(toc, 589);

    assertEquals(1, chapters.size());
    assertEquals("Electrodynamics and Relativity", chapters.getFirst().header);
    assertEquals(502, chapters.getFirst().start);
    assertEquals(574, chapters.getFirst().end);
  }

  @Test
  void numberedStyle_prefaceBeforeChapters_doesNotTriggerAppendixBoundary() {
    String toc = """
        Preface 1
        Foreword 3
        1 Intro 5
        2 Setup 10
        """;

    List<Chapter> chapters = convertTableOfContentsToChapterList(toc, 20);

    assertEquals(2, chapters.size());
    assertEquals("Intro", chapters.getFirst().header);
    assertEquals(5, chapters.getFirst().start);
    assertEquals(9, chapters.getFirst().end);
    assertEquals("Setup", chapters.getLast().header);
    assertEquals(10, chapters.getLast().start);
    assertEquals(20, chapters.getLast().end);
  }

  @Test
  void determineStyle_prefersChapter_whenBothFormatsPresent() {
    String toc = """
        1 Preface 1
        Chapter 1: Real start 5
        Chapter 2: Next 10
        """;

    List<Chapter> chapters = convertTableOfContentsToChapterList(toc, 20);

    assertEquals(2, chapters.size());
    assertEquals("Chapter 1: Real start", chapters.getFirst().header);
    assertEquals("Chapter 2: Next", chapters.getLast().header);
  }

  @Test
  void handlesWindowsLineEndingsAndExtraWhitespace() {
    String toc = "  Chapter 1: Intro   1\r\n\tChapter 2: Setup\t5  \r\n";
    List<Chapter> chapters = convertTableOfContentsToChapterList(toc, 9);

    assertEquals(2, chapters.size());
    assertEquals(1, chapters.getFirst().start);
    assertEquals(4, chapters.getFirst().end);
  }

  @Test
  void convert_throwsIfLastDocumentPageInvalid() {
    assertThrows(IllegalArgumentException.class,
        () -> convertTableOfContentsToChapterList("Chapter 1: Intro 1", 0));
    assertThrows(IllegalArgumentException.class,
        () -> convertTableOfContentsToChapterList("1 Intro 1", -5));
  }

  @Test
  void wordAppendixInTitle_doesNotTriggerAppendixHandling() {
    String toc = """
        Chapter 1: Intro 1
        Chapter 2: Appendix explained 10
        """;

    List<Chapter> chapters = convertTableOfContentsToChapterList(toc, 30);

    assertEquals(2, chapters.size());
    assertEquals(9, chapters.getFirst().end);
    assertEquals(30, chapters.getLast().end);
  }
}
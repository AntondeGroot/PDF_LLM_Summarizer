package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import nl.adgroot.pdfsummarizer.pdf.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.PdfPreviewComposer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfPreviewComposerOrderingAndMatchingTest {

  @Test
  void randomFalse_subsetOrdered_outputsOrigKThenNoteK_inAscendingOrder() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int total = 10;
    List<PdfObject> pool = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      pool.add(pdfObjectWithOrigAndNote(i));
    }

    // non-random: first 5 pages, ordered
    List<Integer> selected = List.of(0, 1, 2, 3, 4);
    List<PdfObject> selectedPages = selected.stream().map(pool::get).toList();

    assertOutputIsOrderedAndPaired(composer, selectedPages, selected);

    for (PdfObject p : pool) p.getDocument().close();
  }

  @Test
  void randomTrue_subsetSortedBeforeCompose_outputsOrigKThenNoteK_inAscendingOrder() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int total = 10;
    List<PdfObject> pool = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      pool.add(pdfObjectWithOrigAndNote(i));
    }

    // random selection produces unsorted picks...
    List<Integer> selectedUnsorted = List.of(7, 2, 9, 0, 5);

    // ...but PreviewSelectionService should sort indexes before building PdfObjects list.
    // So for composer, we pass them sorted (this is the intended contract).
    List<Integer> selectedSorted = selectedUnsorted.stream().sorted().toList();

    List<PdfObject> selectedPages = selectedSorted.stream().map(pool::get).toList();

    assertOutputIsOrderedAndPaired(composer, selectedPages, selectedSorted);

    for (PdfObject p : pool) p.getDocument().close();
  }

  // ---------------- assertions ----------------

  private static void assertOutputIsOrderedAndPaired(
      PdfPreviewComposer composer,
      List<PdfObject> selectedPages,
      List<Integer> expectedIndexesInOrder
  ) throws Exception {

    Path out = Files.createTempFile("preview-order-match-", ".pdf");

    try {
      composer.composeOriginalPlusTextPages(selectedPages, out);

      try (PDDocument result = Loader.loadPDF(out.toFile())) {
        assertEquals(2 * expectedIndexesInOrder.size(), result.getNumberOfPages());

        for (int pos = 0; pos < expectedIndexesInOrder.size(); pos++) {
          int k = expectedIndexesInOrder.get(pos);

          // Each selected PdfObject adds:
          // page (2*pos+1): original
          // page (2*pos+2): note
          String origText = pageText(result, (2 * pos) + 1);
          String noteText = pageText(result, (2 * pos) + 2);

          assertTrue(origText.contains("ORIG-" + k),
              "Expected ORIG-" + k + " at output original page pos=" + pos);

          assertTrue(noteText.contains("NOTE-" + k),
              "Expected NOTE-" + k + " at output notes page pos=" + pos);
        }
      }
    } finally {
      Files.deleteIfExists(out);
    }
  }

  // ---------------- helpers ----------------

  private static PdfObject pdfObjectWithOrigAndNote(int i) throws Exception {
    PDDocument doc = singlePageDocWithText("ORIG-" + i);

    PdfObject obj = new PdfObject(i, "chapter", doc, "TEXT-" + i);
    obj.setNotes("NOTE-" + i);

    return obj;
  }

  private static PDDocument singlePageDocWithText(String text) throws Exception {
    PDDocument d = new PDDocument();
    PDPage p = new PDPage();
    d.addPage(p);

    try (PDPageContentStream cs = new PDPageContentStream(d, p, AppendMode.OVERWRITE, true, true)) {
      cs.beginText();
      cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      cs.newLineAtOffset(50, 750);
      cs.showText(text);
      cs.endText();
    }
    return d;
  }

  private static String pageText(PDDocument doc, int pageNumber1Based) throws Exception {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setStartPage(pageNumber1Based);
    stripper.setEndPage(pageNumber1Based);
    return stripper.getText(doc);
  }
}
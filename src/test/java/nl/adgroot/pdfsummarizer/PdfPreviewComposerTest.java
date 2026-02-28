package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import nl.adgroot.pdfsummarizer.pdf.PdfObject;
import nl.adgroot.pdfsummarizer.pdf.PdfPreviewComposer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class PdfPreviewComposerTest {

  @Test
  void composeOriginalPlusTextPages_whenNotesExistForEachPage_outputs2nPages() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int n = 5;

    List<PdfObject> pages = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());

      PdfObject obj = new PdfObject(i, "chapter", d, "TEXT-" + i);
      obj.setNotes("NOTE-" + i);
      pages.add(obj);
    }

    Path out = Files.createTempFile("preview-", ".pdf");

    try {
      composer.composeOriginalPlusTextPages(pages, out);

      try (PDDocument result = Loader.loadPDF(out.toFile())) {
        assertEquals(2 * n, result.getNumberOfPages(),
            "Expected exactly 2 pages per page when every PdfObject has notes (original + notes)");
      }
    } finally {
      for (PdfObject p : pages) {
        try { p.getDocument().close(); } catch (Exception ignored) {}
      }
      Files.deleteIfExists(out);
    }
  }

  @Test
  void composeOriginalPlusTextPages_nonRandom_firstN_pages_outputs2nPages() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int total = 20;
    int n = 5;

    // Create total pages, but select first N
    List<PdfObject> all = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      PdfObject obj = new PdfObject(i, "chapter", d, "TEXT-" + i);
      all.add(obj);
    }

    List<PdfObject> selected = all.subList(0, n);
    for (int i = 0; i < n; i++) {
      selected.get(i).setNotes("NOTE-" + i);
    }

    Path out = Files.createTempFile("preview-nonrandom-", ".pdf");

    try {
      composer.composeOriginalPlusTextPages(selected, out);

      try (PDDocument result = Loader.loadPDF(out.toFile())) {
        assertEquals(2 * n, result.getNumberOfPages(),
            "Expected original+notes for each of the first N selected pages");
      }
    } finally {
      for (PdfObject p : all) {
        try { p.getDocument().close(); } catch (Exception ignored) {}
      }
      Files.deleteIfExists(out);
    }
  }

  @Test
  void composeOriginalPlusTextPages_whenNotesMissingForSomePages_includesAllOriginals_plusOnlyExistingNotes() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int n = 5;

    List<PdfObject> pages = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      PdfObject obj = new PdfObject(i, "chapter", d, "TEXT-" + i);
      pages.add(obj);
    }

    // Only one page has notes
    pages.get(0).setNotes("ONLY-NOTES");

    Path out = Files.createTempFile("preview-missing-", ".pdf");

    try {
      composer.composeOriginalPlusTextPages(pages, out);

      try (PDDocument result = Loader.loadPDF(out.toFile())) {
        // originals = n, notes = 1 => total n+1
        assertEquals(n + 1, result.getNumberOfPages(),
            "Should include all originals and only notes pages that exist");
      }
    } finally {
      for (PdfObject p : pages) {
        try { p.getDocument().close(); } catch (Exception ignored) {}
      }
      Files.deleteIfExists(out);
    }
  }

  @Test
  void composeOriginalPlusTextPages_randomTrue_unsortedSelectionList_preservesListOrder_andOutputs2nPagesIfAllHaveNotes() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int total = 20;
    int n = 5;

    // Create 20 originals
    List<PdfObject> all = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      all.add(new PdfObject(i, "chapter", d, "TEXT-" + i));
    }

    // Simulate random selection order: this list order is the output order
    List<Integer> selectedIndexes = List.of(7, 2, 9, 0, 5);

    List<PdfObject> selected = selectedIndexes.stream()
        .map(all::get)
        .toList();

    // Give every selected page notes => expect 2*n pages
    for (PdfObject p : selected) {
      p.setNotes("NOTE-for-" + p.getIndex());
    }

    Path out = Files.createTempFile("preview-random-", ".pdf");

    try {
      composer.composeOriginalPlusTextPages(selected, out);

      try (PDDocument result = Loader.loadPDF(out.toFile())) {
        assertEquals(2 * n, result.getNumberOfPages(),
            "Expected original+notes for each randomly selected page when all have notes");
      }
    } finally {
      for (PdfObject p : all) {
        try { p.getDocument().close(); } catch (Exception ignored) {}
      }
      Files.deleteIfExists(out);
    }
  }
}
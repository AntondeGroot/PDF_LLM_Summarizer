package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nl.adgroot.pdfsummarizer.notes.CardsPage;
import nl.adgroot.pdfsummarizer.pdf.PdfPreviewComposer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

class PdfPreviewComposerTest {

  @Test
  void composeOriginalPlusTextPages_whenNotesExistForEachIndex_outputs2nPages() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int n = 5;

    // Build 5 single-page "original" documents
    List<PDDocument> originals = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      originals.add(d);
    }

    // Notes for each page index 0..n-1
    Map<Integer, CardsPage> notesByIndex = new HashMap<>();
    for (int i = 0; i < n; i++) {
      CardsPage cp = new CardsPage();
      cp.addTopic("topic");
      cp.addChapter("chapter");
      cp.addCard("Card " + i);
      notesByIndex.put(i, cp);
    }

    Path out = Files.createTempFile("preview-", ".pdf");

    composer.composeOriginalPlusTextPages(originals, notesByIndex, out);

    try (PDDocument result = Loader.loadPDF(out.toFile())) {
      assertEquals(2 * n, result.getNumberOfPages(),
          "Expected exactly 2 pages per selected original page (original + notes)");
    } finally {
      for (PDDocument d : originals) d.close();
      Files.deleteIfExists(out);
    }
  }

  @Test
  void composeOriginalPlusTextPages_nonRandom_firstN_pages_outputs2nPages() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int total = 20;
    int n = 5;

    // Create 20 original "pages" (each is a 1-page PDDocument)
    List<PDDocument> originals = new java.util.ArrayList<>(total);
    for (int i = 0; i < total; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      originals.add(d);
    }

    // Non-random selection would pick indexes [0..4]
    // Notes exist for exactly those indexes
    Map<Integer, CardsPage> notesByIndex = new HashMap<>();
    for (int i = 0; i < n; i++) {
      CardsPage cp = new CardsPage();
      cp.addTopic("topic");
      cp.addChapter("chapter");
      cp.addCard("Card for page " + i);
      notesByIndex.put(i, cp);
    }

    // Now select the first N originals (this simulates your preview selection)
    List<PDDocument> selectedOriginals = originals.subList(0, n);

    Path out = Files.createTempFile("preview-nonrandom-", ".pdf");

    try {
      composer.composeOriginalPlusTextPages(selectedOriginals, notesByIndex, out);

      try (PDDocument result = Loader.loadPDF(out.toFile())) {
        assertEquals(2 * n, result.getNumberOfPages(),
            "Expected original+notes for each of the first N pages");
      }
    } finally {
      for (PDDocument d : originals) {
        try { d.close(); } catch (Exception ignored) {}
      }
      Files.deleteIfExists(out);
    }
  }

  @Test
  void composeOriginalPlusTextPages_whenNotesMissingForSomeIndexes_stillIncludesAllOriginals() throws Exception {
    PdfPreviewComposer composer = new PdfPreviewComposer();

    int n = 5;

    // Build 5 single-page "original" documents
    List<PDDocument> originals = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      PDDocument d = new PDDocument();
      d.addPage(new PDPage());
      originals.add(d);
    }

    // Simulate the old bug upstream: only ONE notes page exists (e.g., per chapter)
    Map<Integer, CardsPage> notesByIndex = new HashMap<>();
    CardsPage only = new CardsPage();
    only.addTopic("topic");
    only.addChapter("chapter");
    only.addCard("Only one notes page");
    notesByIndex.put(0, only);

    Path out = Files.createTempFile("preview-missing-", ".pdf");

    try {
      composer.composeOriginalPlusTextPages(originals, notesByIndex, out);

      try (PDDocument result = Loader.loadPDF(out.toFile())) {
        // Expected: all originals are still included, plus notes pages that exist.
        // => 5 originals + 1 notes = 6 pages.
        assertEquals(n + 1, result.getNumberOfPages(),
            "Should include all original pages even if notes are missing for some indexes");
      }
    } finally {
      for (PDDocument d : originals) {
        try { d.close(); } catch (Exception ignored) {}
      }
      Files.deleteIfExists(out);
    }
  }
}

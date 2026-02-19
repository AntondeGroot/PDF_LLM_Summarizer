package nl.adgroot.pdfsummarizer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.config.ConfigLoader;
import nl.adgroot.pdfsummarizer.llm.OllamaClient;
import nl.adgroot.pdfsummarizer.notes.Card;
import nl.adgroot.pdfsummarizer.notes.CardParser;
import nl.adgroot.pdfsummarizer.notes.CardsPage;
import nl.adgroot.pdfsummarizer.notes.NotesWriter;
import nl.adgroot.pdfsummarizer.notes.ProgressTracker;
import nl.adgroot.pdfsummarizer.pdf.ParsedPDF;
import nl.adgroot.pdfsummarizer.pdf.PdfBoxTextExtractor;
import nl.adgroot.pdfsummarizer.prompts.PromptTemplate;
import nl.adgroot.pdfsummarizer.text.Chapter;
import nl.adgroot.pdfsummarizer.text.Page;

public class Main {

  public static void main(String[] args) throws Exception {
//    if (args.length < 2) {
//      System.out.println("Usage: java -jar app.jar <input.pdf> <obsidianVaultFolder>");
//      System.exit(1);
//    }

//    Path pdfPath = Paths.get(args[0]);

    Path pdfPath = Paths.get(
        Main.class.getClassLoader().getResource("Learning Docker.pdf").toURI());
//    Path vaultFolder = Paths.get(args[1]);

    // read config
    Path configPath = Paths.get(Main.class.getClassLoader().getResource("config.json").toURI());
    AppConfig cfg = ConfigLoader.load(configPath);

    // init
    PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();
    OllamaClient llm = new OllamaClient(cfg.ollama);
    CardParser parser = new CardParser();
    String topic = filenameToTopic(pdfPath.getFileName().toString());
    NotesWriter writer = new NotesWriter();
    PromptTemplate promptTemplate = PromptTemplate.load(
        Paths.get(Main.class.getClassLoader().getResource("prompt.txt").toURI()));

    // read PDF
    List<String> pagesWithTOC = extractor.extractPages(pdfPath);
    ParsedPDF parsedPdf = new ParsedPDF(pagesWithTOC, cfg.cards.nrOfLinesUsedForContext);

    // loop over PDF to add context to each page

    CardsPage cardsPage;
    int nrPages = parsedPdf.getContent().size();
    ProgressTracker tracker = new ProgressTracker(nrPages);
    for (Chapter chapter : parsedPdf.getTableOfContent()) {
      System.out.println("Processing: " + chapter.title);
      cardsPage = new CardsPage();
      cardsPage.addChapter(chapter.title);
      cardsPage.addTopic(topic);

      List<Page> pages4 = parsedPdf.getContent()
          .stream()
          .filter(c -> c.chapter.equals(chapter.title))
          .toList();
      for (Page page : pages4) {
        ProgressTracker.PageTimer t = tracker.startPage();
        String prompt = promptTemplate.render(Map.of(
            "topic", topic,
            "topicTag", topic.toLowerCase().replace(" ", "-"),
            "section", "test",
            "chunkIndex", "1",
            "chunkCount", "500",
            "created", LocalDate.now().toString(),
            "maxCards", String.valueOf(cfg.cards.maxCardsPerChunk),
            "content", page.toString()
        ));

        String md = llm.generate(prompt);

        List<Card> cards = parser.parse(md);
        for (Card c : cards) {
          cardsPage.addCard(c.toString());
        }// stop timer and record
        long ms = t.elapsedMs();      // elapsed so far (close() also sets it)
        tracker.finishPage(ms);
        // your old percent print, now upgraded:
        System.out.println(tracker.formatStatus(ms));
      }
      Path outDir = Path.of("/Users/adgroot/Documents");
      writer.writeCard(outDir, cardsPage);
      System.out.println("Done. Notes written to: " + outDir.toAbsolutePath());
    }
  }

  private static String filenameToTopic(String filename) {
    String noExt = filename.replaceAll("(?i)\\.pdf$", "");
    return noExt.replace('_', ' ').replace('-', ' ').trim();
  }
}

package nl.adgroot.pdfsummarizer.notes;

import java.util.List;
import nl.adgroot.pdfsummarizer.notes.records.Card;

public interface CardsParser {
  List<Card> parse(String markdown);
}

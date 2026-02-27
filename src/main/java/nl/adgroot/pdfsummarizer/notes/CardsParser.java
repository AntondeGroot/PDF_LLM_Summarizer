package nl.adgroot.pdfsummarizer.notes;

import java.util.List;

public interface CardsParser {
  List<Card> parse(String markdown);
}

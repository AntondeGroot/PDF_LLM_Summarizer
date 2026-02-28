package nl.adgroot.pdfsummarizer.pdf;

import static java.util.Collections.shuffle;

import java.util.ArrayList;
import java.util.List;
import nl.adgroot.pdfsummarizer.config.AppConfig;

/**
 * Pure logic for selecting preview indexes and slicing lists.
 * Keeps preview behavior testable and out of Main/PdfPreparationService.
 */
public class PreviewSelectionService {

  /**
   * Returns the list of selected indexes (sorted ascending) for previewing.
   * If preview is disabled, returns indexes for the full range [0..total-1].
   */
  public List<Integer> selectIndexes(AppConfig cfg, int totalPages) {
    if (totalPages <= 0) return List.of();

    if (cfg == null || cfg.preview == null || !cfg.preview.enabled) {
      return firstIndexes(totalPages);
    }

    int n = Math.min(cfg.preview.nrPages, totalPages);
    if (n <= 0) return List.of();

    return cfg.preview.randomPages
        ? randomIndexes(totalPages, n)
        : firstIndexes(n);
  }

  /** Selects elements by index (keeps original order). */
  public <T> List<T> selectByIndex(List<T> source, List<Integer> indexes) {
    if (source == null || source.isEmpty() || indexes == null || indexes.isEmpty()) return List.of();

    return indexes.stream()
        .sorted()
        .map(source::get)
        .toList();
  }

  private static List<Integer> firstIndexes(int n) {
    List<Integer> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) list.add(i);
    return list;
  }

  private static List<Integer> randomIndexes(int total, int n) {
    List<Integer> all = new ArrayList<>(total);
    for (int i = 0; i < total; i++) all.add(i);

    shuffle(all);

    return all.subList(0, n).stream().sorted().toList();
  }
}
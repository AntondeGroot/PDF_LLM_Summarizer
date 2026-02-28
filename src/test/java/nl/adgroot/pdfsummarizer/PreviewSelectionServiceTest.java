package nl.adgroot.pdfsummarizer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import nl.adgroot.pdfsummarizer.config.AppConfig;
import nl.adgroot.pdfsummarizer.pdf.PreviewSelectionService;
import org.junit.jupiter.api.Test;

class PreviewSelectionServiceTest {

  private final PreviewSelectionService svc = new PreviewSelectionService();

  @Test
  void selectIndexes_previewDisabled_returnsAllIndexes() {
    AppConfig cfg = new AppConfig();
    cfg.preview.enabled = false;

    assertEquals(List.of(0,1,2,3,4), svc.selectIndexes(cfg, 5));
  }

  @Test
  void selectIndexes_previewEnabled_nonRandom_returnsFirstN() {
    AppConfig cfg = new AppConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 3;

    assertEquals(List.of(0,1,2), svc.selectIndexes(cfg, 10));
  }

  @Test
  void selectIndexes_previewEnabled_clampsToTotal() {
    AppConfig cfg = new AppConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = false;
    cfg.preview.nrPages = 99;

    assertEquals(List.of(0,1,2,3), svc.selectIndexes(cfg, 4));
  }

  @Test
  void selectByIndex_keepsOrder() {
    List<String> source = List.of("a","b","c","d","e");
    assertEquals(List.of("b","d"), svc.selectByIndex(source, List.of(3,1)));
  }

  @Test
  void selectIndexes_random_isSortedAndSized() {
    AppConfig cfg = new AppConfig();
    cfg.preview.enabled = true;
    cfg.preview.randomPages = true;
    cfg.preview.nrPages = 4;

    List<Integer> idx = svc.selectIndexes(cfg, 10);

    assertEquals(4, idx.size());
    assertTrue(idx.stream().allMatch(i -> i >= 0 && i < 10));

    // must be sorted
    for (int i = 1; i < idx.size(); i++) {
      assertTrue(idx.get(i - 1) < idx.get(i));
    }
  }
}
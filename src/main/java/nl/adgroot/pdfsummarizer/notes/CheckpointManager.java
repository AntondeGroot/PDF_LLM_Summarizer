package nl.adgroot.pdfsummarizer.notes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import nl.adgroot.pdfsummarizer.AppLogger;

/**
 * Tracks which chapters have been fully written so that a interrupted run can
 * resume the next evening without re-processing completed chapters.
 *
 * <p>State is persisted to {@code <outDir>/checkpoint.json} after every chapter
 * completes. On startup, if that file exists, already-completed chapters are
 * skipped. To start fresh, delete the checkpoint file (or the whole output dir).
 */
public class CheckpointManager {

  private static final AppLogger log = AppLogger.getLogger(CheckpointManager.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path checkpointPath;
  private final State state;

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class State {
    public Set<String> completedChapters = new HashSet<>();
  }

  public CheckpointManager(Path outDir) {
    this.checkpointPath = outDir.resolve("checkpoint.json");
    this.state = loadOrEmpty();
    if (!state.completedChapters.isEmpty()) {
      log.info("Resuming from checkpoint: " + state.completedChapters.size()
          + " chapter(s) already done, skipping them.");
    }
  }

  public boolean isCompleted(String chapterHeader) {
    return state.completedChapters.contains(chapterHeader);
  }

  public synchronized void markCompleted(String chapterHeader) {
    state.completedChapters.add(chapterHeader);
    persist();
  }

  private State loadOrEmpty() {
    if (!Files.exists(checkpointPath)) return new State();
    try {
      return MAPPER.readValue(checkpointPath.toFile(), State.class);
    } catch (IOException e) {
      log.warn("Could not read checkpoint file, starting fresh: " + e.getMessage());
      return new State();
    }
  }

  private void persist() {
    try {
      Files.createDirectories(checkpointPath.getParent());
      MAPPER.writeValue(checkpointPath.toFile(), state);
    } catch (IOException e) {
      log.warn("Could not write checkpoint file: " + e.getMessage());
    }
  }
}
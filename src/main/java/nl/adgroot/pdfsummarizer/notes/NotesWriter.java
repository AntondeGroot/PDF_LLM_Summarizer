package nl.adgroot.pdfsummarizer.notes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class NotesWriter {

  public void writeCard(Path outDir, CardsPage cardsPage) throws IOException {
    String fileName = safeFileName(cardsPage.topic+"-"+cardsPage.chapter) + ".md";
    Path target = uniquify(outDir.resolve(fileName));
    Files.writeString(target, cardsPage.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  public static String safeFileName(String s) {
    String cleaned = (s == null ? "" : s)
        .trim()
        .replaceAll("[\\\\/:*?\"<>|]", "-")
        .replaceAll("\\s+", "-")
        .replaceAll("-{2,}", "-");

    if (cleaned.isEmpty()) cleaned = "Untitled";
    if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120).trim();
    return cleaned;
  }

  private static Path uniquify(Path path) throws IOException {
    if (!Files.exists(path)) return path;

    String fileName = path.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
    String ext = dot >= 0 ? fileName.substring(dot) : "";

    Path dir = path.getParent();
    for (int i = 2; i < 10_000; i++) {
      Path candidate = dir.resolve(base + " (" + i + ")" + ext);
      if (!Files.exists(candidate)) return candidate;
    }
    throw new IOException("Could not find a unique filename for: " + path);
  }
}

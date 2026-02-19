package nl.adgroot.pdfsummarizer.text;

public class Page {
  public String chapter;
  public String content;
  public String contextBefore;
  public String contextAfter;

  public Page(String content){
    this.content = content;
  }

  public void setContextBefore(String context){
    contextBefore = context;
  }

  public void setContextAfter(String context){
    contextAfter = context;
  }

  public String getFirstLines(int nr) {
    if (content == null || content.isBlank() || nr <= 0) {
      return "";
    }

    String[] lines = content.split("\\R"); // handles \n, \r\n, etc.
    int end = Math.min(nr, lines.length);

    return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, end));
  }

  public String getLastLines(int nr) {
    if (content == null || content.isBlank() || nr <= 0) {
      return "";
    }

    String[] lines = content.split("\\R");
    int start = Math.max(0, lines.length - nr);

    return String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length));
  }

  @Override
  public String toString() {
    return "chapter: "+chapter+", content: "+content;
  }
}

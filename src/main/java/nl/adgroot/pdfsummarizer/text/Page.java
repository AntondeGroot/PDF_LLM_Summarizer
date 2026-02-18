package nl.adgroot.pdfsummarizer.text;

public class Page {
  public String chapter;
  public String content;
  public String contextBefore;
  public String contextAfter;

  public Page(String content){
    this.content = content;
  }

  @Override
  public String toString() {
    return "chapter: "+chapter+", content: "+content;
  }
}

package nl.adgroot.pdfsummarizer.text;

public class Chapter {
  public String header;
  public int start;
  public int end;

  public Chapter(String header, int start, int end){
    this.header = header;
    this.start = start;
    this.end = end;
  }

  public Chapter(String title){
    this.header = title;
  }

  @Override
  public String toString() {
    return header+": "+start+"-"+end;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Chapter c)) return false;
    return start == c.start && end == c.end && java.util.Objects.equals(header, c.header);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(header, start, end);
  }
}

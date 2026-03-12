package nl.adgroot.pdfsummarizer.prompts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class PromptTemplate {

  private final String template;

  public PromptTemplate(String template) {
    this.template = template;
  }

  public static PromptTemplate load(Path path) throws IOException {
    String text = Files.readString(path);
    return new PromptTemplate(text);
  }

  public static PromptTemplate loadResource(String name) throws IOException {
    try (InputStream is = PromptTemplate.class.getClassLoader().getResourceAsStream(name)) {
      if (is == null) throw new IOException("Classpath resource not found: " + name);
      return new PromptTemplate(new String(is.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  public String render(Map<String, String> vars) {
    String result = template;

    for (Map.Entry<String, String> e : vars.entrySet()) {
      result = result.replace("{{" + e.getKey() + "}}", e.getValue());
    }

    return result;
  }
}
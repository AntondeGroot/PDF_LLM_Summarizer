package nl.adgroot.pdfsummarizer.text;

import static nl.adgroot.pdfsummarizer.text.TableOfContentConverter.convert;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TableOfContentConverterTest {
  String rawTOC =
    """
    Chapter 35: Running services 130
    Examples 130
    Creating a more advanced service 130
    Creating a simple service 130
    Removing a service 130
    Scaling a service 130
    Chapter 36: Running Simple Node.js Application 131
    Examples 131
    Running a Basic Node.js application inside a Container 131
    Build your image 132
    Running the image 133
    Chapter 37: security 135
    Introduction 135
    Examples 135
    How to find from which image our image comes from 135
    Credits 136""";

  @Test
  void name() {
    List<Chapter> expectedTOC = List.of(
        new Chapter("Chapter 35: Running services", 130, 130),
        new Chapter("Chapter 36: Running Simple Node.js Application", 131, 134),
        new Chapter("Chapter 37: security", 135, 0)
    );

    assertEquals(expectedTOC, convert(rawTOC));
  }
}
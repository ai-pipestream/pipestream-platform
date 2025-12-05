# Usage Guide

## Building the Shaded JAR

To build the shaded JAR file that contains all Tika 4 dependencies:

```bash
./gradlew shadowJar
```

This will create `build/libs/tika4-shaded-4.0.0-SNAPSHOT.jar`

## Using the Shaded JAR

Add the shaded JAR to your project's classpath. All Tika packages have been relocated to `ai.pipestream.shaded.tika` to avoid conflicts.

### Example: Using Tika to Parse a Document

```java
import ai.pipestream.shaded.tika.Tika;
import ai.pipestream.shaded.tika.metadata.Metadata;
import java.io.File;
import java.io.FileInputStream;

public class TikaExample {
    public static void main(String[] args) throws Exception {
        Tika tika = new Tika();
        
        // Parse a document
        File file = new File("document.pdf");
        String content = tika.parseToString(file);
        System.out.println("Extracted content: " + content);
        
        // Get metadata
        Metadata metadata = new Metadata();
        try (FileInputStream fis = new FileInputStream(file)) {
            tika.parse(fis, metadata);
        }
        
        System.out.println("Content-Type: " + metadata.get("Content-Type"));
    }
}
```

### Example: Using Auto-Detection

```java
import ai.pipestream.shaded.tika.parser.AutoDetectParser;
import ai.pipestream.shaded.tika.metadata.Metadata;
import ai.pipestream.shaded.tika.sax.BodyContentHandler;
import java.io.FileInputStream;

public class AutoDetectExample {
    public static void main(String[] args) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        
        try (FileInputStream fis = new FileInputStream("document.docx")) {
            parser.parse(fis, handler, metadata);
        }
        
        System.out.println("Content: " + handler.toString());
        System.out.println("Metadata: " + metadata);
    }
}
```

## OCR Support

The build includes OCR capabilities via Tesseract. To use OCR:

1. Install Tesseract on your system:
   - **Ubuntu/Debian**: `sudo apt-get install tesseract-ocr`
   - **macOS**: `brew install tesseract`
   - **Windows**: Download from https://github.com/UB-Mannheim/tesseract/wiki

2. Configure Tesseract path if needed in your code:

```java
import ai.pipestream.shaded.tika.parser.ocr.TesseractOCRConfig;
import ai.pipestream.shaded.tika.parser.pdf.PDFParserConfig;

TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
ocrConfig.setTesseractPath("/usr/bin/tesseract");
```

## Scientific Document Support

The build includes parsers for scientific document formats including:
- NetCDF
- HDF
- MATLAB
- And other scientific data formats

These parsers work automatically via Tika's auto-detection mechanism.

## Publishing to Local Maven

To install the shaded JAR to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Then reference it in your project:

```xml
<dependency>
    <groupId>ai.pipestream</groupId>
    <artifactId>tika4-shaded</artifactId>
    <version>4.0.0-SNAPSHOT</version>
</dependency>
```

Or in Gradle:

```kotlin
dependencies {
    implementation("ai.pipestream:tika4-shaded:4.0.0-SNAPSHOT")
}
```

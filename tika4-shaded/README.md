# tika4-shaded

A JDK21 Gradle build that takes the latest version of Apache Tika 4 nightly snapshots and shades them into a versioned SNAPSHOT JAR for release.

## Features

This build includes the following Tika 4 components:
- `tika-core` - Core Tika functionality
- `tika-parsers-standard-package` - Standard parsers for common document formats (includes OCR module)
- `tika-parser-scientific-package` - Extended parsers for scientific document formats
- `tika-parser-ocr-module` - OCR support for images and scanned documents (requires tesseract)

Note: The OCR module is included in the standard parsers package but is also added explicitly to ensure availability.

## Requirements

- JDK 21 or later
- Gradle 9.2+ (included via wrapper)
- Network access to https://repository.apache.org/snapshots/ for Tika 4 SNAPSHOT dependencies

## External Tools (Optional)

Tika uses external command-line tools for enhanced parsing capabilities. Install these for full functionality:

### Ubuntu/Debian Installation

```bash
# OCR and Image Processing
sudo apt-get install -y \
    tesseract-ocr \
    tesseract-ocr-all \
    imagemagick

# Media Processing
sudo apt-get install -y \
    ffmpeg \
    libimage-exiftool-perl \
    sox

# Archive Support
sudo apt-get install -y \
    unrar-free

# PDF Processing
sudo apt-get install -y \
    mupdf-tools \
    ghostscript \
    poppler-utils

# Geospatial Data
sudo apt-get install -y \
    gdal-bin

# Microsoft Outlook PST Files
sudo apt-get install -y \
    pst-utils

# File Detection and Analysis
sudo apt-get install -y \
    file \
    binutils
```

### All-in-One Installation

```bash
sudo apt-get update && sudo apt-get install -y \
    tesseract-ocr \
    tesseract-ocr-all \
    imagemagick \
    ffmpeg \
    libimage-exiftool-perl \
    sox \
    unrar-free \
    mupdf-tools \
    ghostscript \
    poppler-utils \
    gdal-bin \
    pst-utils \
    file \
    binutils
```

### Docker Installation

```dockerfile
RUN apt-get update && apt-get install -y \
    tesseract-ocr \
    tesseract-ocr-all \
    imagemagick \
    ffmpeg \
    libimage-exiftool-perl \
    sox \
    unrar-free \
    mupdf-tools \
    ghostscript \
    poppler-utils \
    gdal-bin \
    pst-utils \
    file \
    binutils \
    && rm -rf /var/lib/apt/lists/*
```

### Tool Reference

| Application | Ubuntu Package | Command | Purpose | Project URL |
|-------------|----------------|---------|---------|-------------|
| Tesseract OCR | `tesseract-ocr` | `tesseract` | OCR for images and scanned documents | https://github.com/tesseract-ocr/tesseract |
| Tesseract Languages | `tesseract-ocr-all` | - | All language packs for Tesseract | https://github.com/tesseract-ocr/tessdata |
| ImageMagick | `imagemagick` | `convert` | Image preprocessing for OCR | https://imagemagick.org/ |
| FFmpeg | `ffmpeg` | `ffmpeg` | Video/audio metadata extraction | https://ffmpeg.org/ |
| ExifTool | `libimage-exiftool-perl` | `exiftool` | Image/video EXIF metadata | https://exiftool.org/ |
| SoX | `sox` | `sox` | Audio file processing and metadata | https://sox.sourceforge.net/ |
| UnRAR | `unrar-free` | `unrar` | RAR archive extraction | https://www.rarlab.com/rar_add.htm |
| MuPDF | `mupdf-tools` | `mutool` | PDF to image rendering | https://mupdf.com/ |
| Ghostscript | `ghostscript` | `gs` | PDF/PostScript rendering | https://www.ghostscript.com/ |
| Poppler | `poppler-utils` | `pdftotext` | PDF text extraction | https://poppler.freedesktop.org/ |
| GDAL | `gdal-bin` | `gdalinfo` | Geospatial data formats | https://gdal.org/ |
| libpst | `pst-utils` | `readpst` | Microsoft Outlook PST files | https://www.five-ten-sg.com/libpst/ |
| file | `file` | `file` | MIME type detection (libmagic) | https://www.darwinsys.com/file/ |
| GNU Binutils | `binutils` | `strings` | Binary string extraction | https://www.gnu.org/software/binutils/ |

### Not Available in Standard Repositories

| Application | Command | Purpose | Project URL |
|-------------|---------|---------|-------------|
| LibreDWG | `dwgread` | AutoCAD DWG file parsing | https://github.com/LibreDWG/libredwg |
| Siegfried | `sf` | File format identification | https://github.com/richardlehane/siegfried |
| Magika | `magika` | AI-powered file type detection | https://github.com/google/magika |

**Note:** Tika will work without these tools but with reduced functionality for specific file types. Missing tools are logged at DEBUG level during initialization.

## Building

To build the shaded JAR:

```bash
./gradlew shadowJar
```

The shaded JAR will be created in `build/libs/tika4-shaded-4.0.0-SNAPSHOT.jar`

## Automated Builds

This project includes a GitHub Actions workflow that:
- Builds the shaded JAR automatically on push to main/develop branches
- Runs nightly to capture the latest Tika 4 snapshots
- Uploads the built artifact for easy download
- Publishes to GitHub Packages on the main branch

You can trigger a manual build from the Actions tab in GitHub.

## Publishing

To publish to a local Maven repository:

```bash
./gradlew publishToMavenLocal
```

To publish to GitHub Packages (requires GITHUB_TOKEN):

```bash
export GITHUB_ACTOR=your-username
export GITHUB_TOKEN=your-token
./gradlew publish
```

## About Shading

This build uses the Shadow plugin to relocate all Tika packages from `org.apache.tika` to `ai.pipestream.shaded.tika` to avoid conflicts with other versions of Tika that may be present in the classpath.

See [USAGE.md](USAGE.md) for detailed usage examples.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Note: This is a shaded packaging project. Apache Tika itself is licensed under the Apache License 2.0.


package app.mnema.ai.support;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);
    private static final List<String> DEFAULT_DATA_PATHS = List.of(
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tesseract-ocr/tessdata",
            "/usr/share/tessdata"
    );

    private final String dataPath;

    public TesseractOcrEngine(@Value("${app.ai.import.ocr-data-path:}") String dataPath) {
        this.dataPath = resolveDataPath(dataPath);
    }

    @Override
    public String extractText(BufferedImage image, String language, int dpi) {
        Tesseract tesseract = new Tesseract();
        if (dataPath != null && !dataPath.isBlank()) {
            tesseract.setDatapath(dataPath);
        }
        if (language != null && !language.isBlank()) {
            tesseract.setLanguage(language);
        }
        if (dpi > 0) {
            tesseract.setVariable("user_defined_dpi", Integer.toString(dpi));
        }
        try {
            return tesseract.doOCR(image);
        } catch (TesseractException ex) {
            throw new IllegalStateException("OCR failed", ex);
        }
    }

    private String resolveDataPath(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        for (String path : DEFAULT_DATA_PATHS) {
            if (Files.isDirectory(Path.of(path))) {
                return path;
            }
        }
        log.warn("Tesseract tessdata path not found; relying on system defaults");
        return null;
    }
}

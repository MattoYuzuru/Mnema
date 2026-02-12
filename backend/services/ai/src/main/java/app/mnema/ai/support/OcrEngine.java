package app.mnema.ai.support;

import java.awt.image.BufferedImage;

public interface OcrEngine {
    String extractText(BufferedImage image, String language, int dpi);
}

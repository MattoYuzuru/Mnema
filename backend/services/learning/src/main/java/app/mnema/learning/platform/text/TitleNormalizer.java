package app.mnema.learning.platform.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one normalization of titles that must compare equal: NFC, trimmed, case folded and with every run of whitespace
 * collapsed to one space. The exercise generator uses it to recognize an objective it already knows by its title, at compile
 * time against the offered objectives and at approval against the objectives of the material.
 */
public final class TitleNormalizer {
    private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\s]+");

    private TitleNormalizer() { }

    public static String normalize(String title) {
        String canonical = Normalizer.normalize(title, Normalizer.Form.NFC);
        return SPACES.matcher(canonical.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }
}

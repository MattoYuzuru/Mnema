package app.mnema.learning.ai;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The license allowlist of image search (commercial use must be allowed): the Pixabay Content License, CC0, the Public Domain Mark and
 * public domain, CC BY (any version) and CC BY-SA (any version, {@code shareAlike}). Everything else is dropped: NonCommercial,
 * NoDerivatives, GFDL-only, fair use, unknown or missing.
 */
final class ImageLicense {
    /** An allowed license: the short name shown to the person, its https URL (or null) and whether it is share-alike. */
    record Licensed(String name, String url, boolean shareAlike) { }

    static final Licensed PIXABAY = new Licensed("Pixabay Content License", "https://pixabay.com/service/license-summary/", false);
    private static final Pattern RESTRICTED = Pattern.compile("(?i)(^|[^a-z])(nc|nd)([^a-z]|$)");
    private static final Pattern PUBLIC_DOMAIN = Pattern.compile("(?i)^(public[ -]domain.*|pd([ -].*)?|pdm.*)$");
    private static final Pattern BY = Pattern.compile("(?i)^cc[ -]by(-sa)?[ -]?([1-4]\\.[0-9])?( .*)?$");

    private ImageLicense() { }

    /** Openverse style: {@code license} is a code ({@code by}, {@code by-sa}, {@code cc0}, {@code pdm}), {@code version} may be empty. */
    static Optional<Licensed> fromCode(String code, String version, String url) {
        if (code == null) return Optional.empty();
        String normalized = code.strip().toLowerCase(Locale.ROOT);
        String v = version == null ? "" : version.strip();
        String link = ImageText.https(url);
        return switch (normalized) {
            case "cc0" -> Optional.of(new Licensed("CC0 1.0", link, false));
            case "pdm" -> Optional.of(new Licensed("Public domain", link, false));
            case "by" -> Optional.of(new Licensed("CC BY" + (v.isEmpty() ? "" : " " + v), link, false));
            case "by-sa" -> Optional.of(new Licensed("CC BY-SA" + (v.isEmpty() ? "" : " " + v), link, true));
            default -> Optional.empty();
        };
    }

    /** Wikimedia Commons style: the {@code LicenseShortName} text ({@code CC BY-SA 4.0}, {@code CC0}, {@code Public domain}, ...). */
    static Optional<Licensed> fromShortName(String shortName, String url) {
        if (shortName == null) return Optional.empty();
        String name = ImageText.plain(shortName, 64);
        if (name.isEmpty()) return Optional.empty();
        String link = ImageText.https(url);
        if (name.toUpperCase(Locale.ROOT).startsWith("CC0")) return Optional.of(new Licensed(name, link, false));
        if (PUBLIC_DOMAIN.matcher(name).matches()) return Optional.of(new Licensed(name, link, false));
        Matcher by = BY.matcher(name);
        if (by.matches() && !RESTRICTED.matcher(name).find()) {
            return Optional.of(new Licensed(name, link, name.toLowerCase(Locale.ROOT).startsWith("cc by-sa") || name.toLowerCase(Locale.ROOT).startsWith("cc-by-sa")));
        }
        return Optional.empty();
    }
}

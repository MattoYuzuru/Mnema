package app.mnema.ai.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ImportItemExtractor {

    private static final Pattern BULLET_PREFIX = Pattern.compile("^\\s*(?:[-*]+|\\d+[\\).]|\\([a-zA-Z0-9]+\\))\\s*");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final int MIN_LIST_LINES = 5;
    private static final int MAX_WORDS_PER_ITEM = 6;
    private static final double MIN_SHORT_LINE_RATIO = 0.6;

    private ImportItemExtractor() {
    }

    public static List<String> extractItems(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] lines = text.split("\\R+");
        List<String> cleaned = new ArrayList<>();
        int nonEmptyLines = 0;
        int shortLines = 0;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            nonEmptyLines++;
            String normalized = BULLET_PREFIX.matcher(trimmed).replaceFirst("").trim();
            normalized = MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
            if (normalized.isEmpty()) {
                continue;
            }
            cleaned.add(normalized);
            if (countWords(normalized) <= MAX_WORDS_PER_ITEM) {
                shortLines++;
            }
        }
        if (nonEmptyLines < MIN_LIST_LINES) {
            return List.of();
        }
        int requiredShortLines = Math.max(3, (int) Math.ceil(nonEmptyLines * MIN_SHORT_LINE_RATIO));
        if (shortLines < requiredShortLines) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String item : cleaned) {
            String key = item.toLowerCase(Locale.ROOT);
            unique.putIfAbsent(key, item);
        }
        return List.copyOf(unique.values());
    }

    private static int countWords(String text) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (inWord) {
                    count++;
                    inWord = false;
                }
            } else {
                inWord = true;
            }
        }
        if (inWord) {
            count++;
        }
        return count;
    }
}

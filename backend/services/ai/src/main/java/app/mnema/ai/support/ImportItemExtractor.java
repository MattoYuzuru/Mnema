package app.mnema.ai.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ImportItemExtractor {

    private static final Pattern NUMBERED_PREFIX = Pattern.compile("^\\s*(\\d+)[\\).]\\s*(.+)\\s*$");
    private static final Pattern BULLET_PREFIX = Pattern.compile("^\\s*(?:[-*]+|\\d+[\\).]|\\([a-zA-Z0-9]+\\))\\s*");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final int MIN_LIST_LINES = 5;
    private static final int MAX_WORDS_PER_ITEM = 6;
    private static final double MIN_SHORT_LINE_RATIO = 0.6;

    private ImportItemExtractor() {
    }

    public static List<String> extractItems(String text) {
        return extract(text).items().stream()
                .map(SourceItem::text)
                .toList();
    }

    public static ItemExtraction extract(String text) {
        if (text == null || text.isBlank()) {
            return ItemExtraction.empty();
        }
        List<LineItem> numbered = extractNumberedItems(text);
        if (numbered.size() >= MIN_LIST_LINES) {
            return buildExtraction(numbered, true);
        }

        String[] lines = text.split("\\R+");
        List<LineItem> cleaned = new ArrayList<>();
        int nonEmptyLines = 0;
        int shortLines = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
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
            cleaned.add(new LineItem(cleaned.size() + 1, i + 1, normalized));
            if (countWords(normalized) <= MAX_WORDS_PER_ITEM) {
                shortLines++;
            }
        }
        if (nonEmptyLines < MIN_LIST_LINES) {
            return ItemExtraction.empty();
        }
        int requiredShortLines = Math.max(3, (int) Math.ceil(nonEmptyLines * MIN_SHORT_LINE_RATIO));
        if (shortLines < requiredShortLines) {
            return ItemExtraction.empty();
        }
        return buildExtraction(cleaned, false);
    }

    private static List<LineItem> extractNumberedItems(String text) {
        String[] lines = text.split("\\R+");
        List<LineItem> items = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) {
                continue;
            }
            var matcher = NUMBERED_PREFIX.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int sourceIndex;
            try {
                sourceIndex = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            String textValue = MULTI_SPACE.matcher(matcher.group(2)).replaceAll(" ").trim();
            if (!textValue.isEmpty()) {
                items.add(new LineItem(sourceIndex, i + 1, textValue));
            }
        }
        return items;
    }

    private static ItemExtraction buildExtraction(List<LineItem> cleaned, boolean numbered) {
        Map<String, String> unique = new LinkedHashMap<>();
        Map<String, SourceItem> sourceItemsByKey = new LinkedHashMap<>();
        int duplicates = 0;
        for (LineItem item : cleaned) {
            String key = normalizeKey(item.text());
            String previous = unique.putIfAbsent(key, item.text());
            if (previous != null) {
                duplicates++;
                continue;
            }
            sourceItemsByKey.put(key, new SourceItem(item.sourceIndex(), item.lineNumber(), item.text(), key));
        }
        return new ItemExtraction(
                List.copyOf(sourceItemsByKey.values()),
                duplicates,
                numbered ? missingNumbers(cleaned) : List.of(),
                numbered
        );
    }

    private static List<Integer> missingNumbers(List<LineItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (LineItem item : items) {
            min = Math.min(min, item.sourceIndex());
            max = Math.max(max, item.sourceIndex());
            present.add(item.sourceIndex());
        }
        if (min == Integer.MAX_VALUE || max <= min) {
            return List.of();
        }
        List<Integer> missing = new ArrayList<>();
        for (int number = min; number <= max; number++) {
            if (!present.contains(number)) {
                missing.add(number);
            }
        }
        return List.copyOf(missing);
    }

    public static String normalizeKey(String text) {
        if (text == null) {
            return "";
        }
        return MULTI_SPACE.matcher(text.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
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

    private record LineItem(int sourceIndex, int lineNumber, String text) {
    }

    public record SourceItem(int sourceIndex, int lineNumber, String text, String normalizedKey) {
    }

    public record ItemExtraction(List<SourceItem> items,
                                 int duplicatesSkipped,
                                 List<Integer> missingNumbers,
                                 boolean numbered) {
        private static ItemExtraction empty() {
            return new ItemExtraction(List.of(), 0, List.of(), false);
        }
    }
}

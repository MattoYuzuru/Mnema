package app.mnema.importer.service;

import java.util.Locale;
import java.util.Set;

final class ImportFieldTypeSupport {

    private static final Set<String> OVERRIDABLE_FIELD_TYPES = Set.of(
            "text",
            "rich_text",
            "markdown",
            "image",
            "audio",
            "video"
    );

    private ImportFieldTypeSupport() {
    }

    static boolean isMediaField(String fieldType) {
        if (fieldType == null) {
            return false;
        }
        String normalized = fieldType.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("image") || normalized.equals("audio") || normalized.equals("video");
    }

    static String inferFieldType(String name) {
        String lowered = normalize(name);
        if (lowered.contains("image") || lowered.contains("img") || lowered.contains("picture") || lowered.contains("photo") || lowered.contains("pic")) {
            return "image";
        }
        if (lowered.contains("audio") || lowered.contains("sound")) {
            return "audio";
        }
        if (lowered.contains("video")) {
            return "video";
        }
        return "text";
    }

    static String normalizeRequestedFieldType(String requestedType) {
        if (requestedType == null) {
            return null;
        }
        String normalized = requestedType.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return OVERRIDABLE_FIELD_TYPES.contains(normalized) ? normalized : null;
    }

    static String resolveFieldType(String name, String requestedType) {
        String override = normalizeRequestedFieldType(requestedType);
        return override != null ? override : inferFieldType(name);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}

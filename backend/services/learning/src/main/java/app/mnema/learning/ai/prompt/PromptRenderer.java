package app.mnema.learning.ai.prompt;

import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Matcher;

/**
 * One-pass placeholder rendering. The body of a section is scanned once: each {@code {{name}}} is replaced by its value
 * and the inserted text is never scanned again, so a placeholder inside a note, a material or an answer stays inert.
 * A required placeholder without a value is a hard {@link PromptException}, never an empty string; a placeholder with a
 * literal default ({@code {{name|"default"}}}) falls back to it when the value is absent or blank.
 *
 * <p>Text values are redacted ({@link Redactor}) and then escaped ({@code & < > "} to entities). A name ending in
 * {@code _json} is inserted as a JSON string (a learner answer) and escaped for {@code & < >} only, since JSON quoting
 * already protects the quotes. Block values are inserted verbatim.
 */
public final class PromptRenderer {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String render(PromptSection section, PromptValues values) {
        if (section.isStatic()) return section.body();
        Matcher matcher = PromptLibrary.PLACEHOLDER.matcher(section.body());
        StringBuilder out = new StringBuilder(section.body().length() + 256);
        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolve(section, name, fallback, values)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String resolve(PromptSection section, String name, String fallback, PromptValues values) {
        boolean block = PromptValues.isBlockName(name);
        PromptValues.Value value = values.get(name);
        if (block) {
            if (value == null) throw new PromptException("Unresolved placeholder " + name + " in " + section.name());
            return value.text();
        }
        if (value == null || value.text().isBlank()) {
            if (fallback != null) return fallback;
            throw new PromptException("Unresolved placeholder " + name + " in " + section.name());
        }
        String clean = Redactor.redact(value.text());
        if (name.endsWith("_json")) return escape(JSON.writeValueAsString(clean), false);
        return escape(clean, true);
    }

    /** Escapes text for element content and attribute values. */
    static String escape(String text, boolean quotes) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append(quotes ? "&quot;" : "\"");
                default -> out.append(character);
            }
        }
        return out.toString();
    }
}

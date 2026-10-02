package app.mnema.learning.ai.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry of the versioned prompt sections under {@code ai/prompts/<version>/**}. A released version is immutable
 * (a change is a new directory), so a section is addressed by {@code (prompt_version, section)} and the version is
 * what an artifact revision records. Loading is strict: unknown or duplicate front matter keys, a version that differs
 * from its directory, a duplicate section name, a malformed placeholder, or a placeholder in a static layer fails fast at
 * startup instead of surfacing as a bad prompt later.
 */
public final class PromptLibrary {
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("/ai/prompts/(v[0-9]{1,3})/");
    static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*(?:\\|\\s*\"([^\"]*)\")?\\s*\\}\\}");
    private static final Set<String> KEYS = Set.of("section", "prompt_version", "purpose");

    private final String activeVersion;
    private final Map<String, Map<String, PromptSection>> versions;

    private PromptLibrary(String activeVersion, Map<String, Map<String, PromptSection>> versions) {
        if (!versions.containsKey(activeVersion)) throw new PromptException("Active prompt version has no sections");
        this.activeVersion = activeVersion;
        this.versions = versions;
    }

    /** Loads every {@code ai/prompts/v*} directory of the classpath. */
    public static PromptLibrary fromClasspath(String activeVersion) {
        List<PromptSection> sections = new ArrayList<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:ai/prompts/v*/**/*.md")) {
                String url = resource.getURL().toString();
                Matcher directory = VERSION_DIRECTORY.matcher(url);
                if (!directory.find()) continue;
                sections.add(parse(directory.group(1), resource.getFilename(),
                        new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            }
        } catch (IOException exception) {
            throw new PromptException("Prompt resources are unreadable");
        }
        return of(activeVersion, sections);
    }

    public static PromptLibrary of(String activeVersion, Collection<PromptSection> sections) {
        Map<String, Map<String, PromptSection>> byVersion = new TreeMap<>();
        for (PromptSection section : sections) {
            if (byVersion.computeIfAbsent(section.version(), key -> new LinkedHashMap<>())
                    .putIfAbsent(section.name(), section) != null) {
                throw new PromptException("Duplicate prompt section " + section.name() + " in " + section.version());
            }
        }
        return new PromptLibrary(activeVersion, byVersion);
    }

    /** Parses one file; {@code directoryVersion} is the {@code v1} directory the file lives in. */
    public static PromptSection parse(String directoryVersion, String fileName, String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw new PromptException(fileName + ": front matter must open with ---");
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) throw new PromptException(fileName + ": front matter is not closed");
        Map<String, String> front = new LinkedHashMap<>();
        for (String line : text.substring(4, end).split("\n")) {
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon < 1) throw new PromptException(fileName + ": malformed front matter line");
            String key = line.substring(0, colon).strip();
            if (!KEYS.contains(key)) throw new PromptException(fileName + ": unknown front matter key " + key);
            if (front.put(key, scalar(line.substring(colon + 1).strip())) != null) {
                throw new PromptException(fileName + ": duplicate front matter key " + key);
            }
        }
        for (String key : KEYS) {
            if (front.getOrDefault(key, "").isEmpty()) throw new PromptException(fileName + ": missing " + key);
        }
        if (!front.get("prompt_version").equals(directoryVersion)) {
            throw new PromptException(fileName + ": prompt_version differs from its directory");
        }
        String body = text.substring(end + 5).replaceAll("\n+$", "");
        List<PromptSection.Placeholder> placeholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) placeholders.add(new PromptSection.Placeholder(matcher.group(1), matcher.group(2)));
        if (PLACEHOLDER.matcher(body).replaceAll("").contains("{{")) {
            throw new PromptException(fileName + ": malformed placeholder");
        }
        String name = front.get("section");
        boolean staticLayer = name.equals("system") || name.equals("style") || name.startsWith("skill-");
        if (staticLayer && !placeholders.isEmpty()) {
            throw new PromptException(fileName + ": a static layer must not contain placeholders");
        }
        return new PromptSection(directoryVersion, name, front.get("purpose"), body, placeholders);
    }

    private static String scalar(String value) {
        if (value.startsWith("\"")) {
            int close = value.lastIndexOf('"');
            if (close < 1) throw new PromptException("Unterminated quoted front matter value");
            return value.substring(1, close);
        }
        int comment = value.indexOf(" #");
        return (comment < 0 ? value : value.substring(0, comment)).strip();
    }

    public String version() { return activeVersion; }

    public Set<String> versions() { return new TreeSet<>(versions.keySet()); }

    /** The section of the active version. */
    public PromptSection section(String name) { return section(activeVersion, name); }

    public PromptSection section(String version, String name) {
        PromptSection section = versions.getOrDefault(version, Map.of()).get(name);
        if (section == null) throw new PromptException("Unknown prompt section " + name + " in " + version);
        return section;
    }
}

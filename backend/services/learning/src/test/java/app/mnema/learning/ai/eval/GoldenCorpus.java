package app.mnema.learning.ai.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The golden eval corpus of {@code contracts/generation/eval} (no database, no network): the four fixture files, the exemplars
 * that fill the deck brief of every prompt, and the manifest that points at the answer-check golden set of
 * {@code contracts/study/assessment-golden}.
 */
public final class GoldenCorpus {
    public static final JsonMapper JSON = JsonMapper.builder().build();

    /** What a fixture asks the pipeline to do; {@link #wire} is the {@code kind} member of the file. */
    public enum Kind {
        MATERIAL_FROM_NOTES("material-from-notes", "notes.json"),
        MATERIAL_FROM_PROMPT("material-from-prompt", "prompts.json"),
        EDIT("edit", "edits.json"),
        EXERCISE("exercise", "exercises.json");

        private final String wire;
        private final String file;

        Kind(String wire, String file) {
            this.wire = wire;
            this.file = file;
        }

        public String wire() { return wire; }

        public String file() { return file; }

        public boolean material() { return this == MATERIAL_FROM_NOTES || this == MATERIAL_FROM_PROMPT; }

        static Kind of(String wire) {
            for (Kind kind : values()) if (kind.wire.equals(wire)) return kind;
            throw new IllegalArgumentException("Unknown fixture kind: " + wire);
        }
    }

    /**
     * One fixture. {@code input} and {@code expect} keep the shape of {@code fixture.schema.json}; they carry text written for the corpus
     * (no personal data), so a fixture may be printed, but the runner still writes ids and numbers only into its reports.
     */
    public record Fixture(String id, Kind kind, String language, String outputLanguage, boolean heldOut, List<String> tags,
                          JsonNode input, JsonNode expect) {
        public List<String> expected(String member) {
            List<String> values = new ArrayList<>();
            expect.path(member).forEach(value -> values.add(value.stringValue("")));
            return values;
        }
    }

    private GoldenCorpus() { }

    /** The directory of the corpus. */
    public static Path directory() { return root().resolve("contracts/generation/eval"); }

    public static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/states.json"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find repository root");
        return root;
    }

    /** The parsed JSON of a file of the corpus, relative to the corpus directory. */
    public static JsonNode read(String relative) {
        try {
            return JSON.readTree(Files.readString(directory().resolve(relative), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Unreadable corpus file " + relative, failure);
        }
    }

    /** Every fixture of the four kinds, in file order. */
    public static List<Fixture> load() {
        List<Fixture> fixtures = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (JsonNode node : read("fixtures/" + kind.file()).path("fixtures")) {
                List<String> tags = new ArrayList<>();
                node.path("tags").forEach(tag -> tags.add(tag.stringValue("")));
                fixtures.add(new Fixture(node.path("id").stringValue(""), Kind.of(node.path("kind").stringValue("")),
                        node.path("language").stringValue(""), node.path("outputLanguage").stringValue(""),
                        node.path("heldOut").booleanValue(false), List.copyOf(tags), node.path("input"), node.path("expect")));
            }
        }
        return List.copyOf(fixtures);
    }

    /** Exemplar MBM by language: the author's sample in the deck brief, and the text the copy check compares outputs with. */
    public static Map<String, String> exemplars() {
        Map<String, String> exemplars = new LinkedHashMap<>();
        read("fixtures/exemplars.json").path("exemplars").properties().forEach(entry -> exemplars.put(entry.getKey(), entry.getValue().stringValue("")));
        return Map.copyOf(exemplars);
    }

    /** The manifest of the answer checks (the golden set is referenced, never copied). */
    public static JsonNode answerChecks() { return read("fixtures/answer-checks.json"); }
}

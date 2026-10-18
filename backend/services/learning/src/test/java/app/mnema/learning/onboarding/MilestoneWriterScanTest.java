package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source side of the inventory (OB-301): the {@code writer} of every milestone in {@code contracts/onboarding/milestones.json} names classes and methods that exist and that call
 * the recorder for that key, and no other class of the application calls it for that key. A new writer without a fixture row, or a fixture row whose call was lost in a refactoring,
 * fails here. {@code FIRST_SESSION} and {@code TWO_DAYS} are written by the recorder's {@code sessionCompleted}, which the two completing writers call.
 */
class MilestoneWriterScanTest {
    private static final Pattern WRITER = Pattern.compile("([A-Z][A-Za-z0-9]*)\\.([a-z][A-Za-z0-9]*)");
    private static final Pattern KEY = Pattern.compile("MilestoneKey\\.([A-Z_]+)");
    private static final Set<String> SESSION_KEYS = Set.of("FIRST_SESSION", "TWO_DAYS");

    private static final Path SOURCES = MilestoneFixture.root().resolve("backend/services/learning/src/main/java");
    private static final Map<String, Path> CLASSES = classes();

    private static Map<String, Path> classes() {
        Map<String, Path> classes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> classes.put(file.getFileName().toString().replace(".java", ""), file));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return classes;
    }

    private static String source(String type) {
        Path file = CLASSES.get(type);
        assertThat(file).as("the class %s named by a writer of the fixture", type).isNotNull();
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Fixture writer text to its {@code Class.method} pairs: "ItemService.apply (create)" is one, "A.x, B.y" two. */
    private static Map<String, String> writers(JsonNode milestone) {
        Map<String, String> writers = new LinkedHashMap<>();
        String text = milestone.path("writer").stringValue(null);
        if (text == null) return writers;
        for (String part : text.split(",")) {
            Matcher matcher = WRITER.matcher(part.strip());
            assertThat(matcher.find()).as("writer %s of %s is Class.method", part, milestone.path("key").stringValue()).isTrue();
            writers.put(matcher.group(1), matcher.group(2));
        }
        return writers;
    }

    @Test
    void everyWriterOfTheFixtureExistsAndCallsTheRecorderForItsKey() {
        for (JsonNode milestone : MilestoneFixture.REGISTRY.path("milestones")) {
            String key = milestone.path("key").stringValue();
            Map<String, String> writers = writers(milestone);
            writers.forEach((type, method) -> {
                String source = source(type);
                assertThat(source).as("%s names %s.%s", key, type, method).containsPattern("\\b" + method + "\\(");
                if (SESSION_KEYS.contains(key)) {
                    assertThat(source).as("%s calls the recorder for the key %s", type, key).contains(".sessionCompleted(");
                } else {
                    assertThat(source).as("%s calls the recorder for the key %s", type, key).contains("MilestoneKey." + key);
                    assertThat(source).as("%s holds the recorder", type).contains("MilestoneRecorder");
                }
            });
            if (SESSION_KEYS.contains(key)) assertThat(source("MilestoneRecorder")).contains("MilestoneKey." + key);
            if (writers.isEmpty()) assertThat(milestone.path("writer").isNull()).as("%s has no writer in registry version 1", key).isTrue();
        }
    }

    @Test
    void noClassOutsideTheFixtureCallsTheRecorderForAKey() {
        Map<String, Set<String>> declared = new LinkedHashMap<>();
        for (JsonNode milestone : MilestoneFixture.REGISTRY.path("milestones")) {
            declared.put(milestone.path("key").stringValue(), writers(milestone).keySet());
        }
        List<String> undeclared = new ArrayList<>();
        Set<String> sessionCallers = new TreeSet<>();
        for (Map.Entry<String, Path> entry : CLASSES.entrySet()) {
            // the registry, the recorder and the code of the path itself are the onboarding package
            if (entry.getValue().toString().contains("/learning/onboarding/")) continue;
            String source = source(entry.getKey());
            Matcher keys = KEY.matcher(source);
            while (keys.find()) {
                String key = keys.group(1);
                if (!declared.containsKey(key) || !declared.get(key).contains(entry.getKey())) undeclared.add(entry.getKey() + " writes " + key);
            }
            if (source.contains(".sessionCompleted(")) sessionCallers.add(entry.getKey());
        }
        assertThat(undeclared).as("a class that records a milestone must be the writer named in contracts/onboarding/milestones.json").isEmpty();
        assertThat(sessionCallers).containsExactlyInAnyOrderElementsOf(declared.get("FIRST_SESSION"));
        assertThat(declared.get("FIRST_SESSION")).isEqualTo(declared.get("TWO_DAYS"));
        assertThat(declared.get("RHYTHM_WEEK")).isEmpty();
    }
}

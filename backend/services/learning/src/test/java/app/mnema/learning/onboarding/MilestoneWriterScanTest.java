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
 * fails here. {@code FIRST_SESSION} and {@code TWO_DAYS} are written by the recorder's {@code sessionCompleted}, which the two completing writers call; {@code RHYTHM_WEEK} by
 * {@code WeeklyRhythm.sessionCompleted}, which the same two call right after it, and by the preferences route through {@code WeeklyRhythm.targetChanged}.
 */
class MilestoneWriterScanTest {
    private static final Pattern WRITER = Pattern.compile("([A-Z][A-Za-z0-9]*)\\.([a-z][A-Za-z0-9]*)");
    private static final Pattern KEY = Pattern.compile("MilestoneKey\\.([A-Z_]+)");
    private static final Set<String> SESSION_KEYS = Set.of("FIRST_SESSION", "TWO_DAYS");
    private static final String RHYTHM_KEY = "RHYTHM_WEEK";

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

    /**
     * Fixture writer text to its {@code Class.method} pairs, per class: "ItemService.apply (create)" is one, "CaptureService.create, CaptureService.archive" two methods of one class.
     * A part that names a route ("PUT /api/onboarding/preferences when ...") is not a method; {@link MilestoneInventoryIntegrationTest} holds the routes.
     */
    private static Map<String, List<String>> writers(JsonNode milestone) {
        Map<String, List<String>> writers = new LinkedHashMap<>();
        String text = milestone.path("writer").stringValue(null);
        if (text == null) return writers;
        for (String part : text.split(",")) {
            if (part.strip().matches("(GET|PUT|POST|PATCH|DELETE) /api/.*")) continue;
            Matcher matcher = WRITER.matcher(part.strip());
            assertThat(matcher.find()).as("writer %s of %s is Class.method", part, milestone.path("key").stringValue()).isTrue();
            writers.computeIfAbsent(matcher.group(1), type -> new ArrayList<>()).add(matcher.group(2));
        }
        return writers;
    }

    /** The bodies of every declaration of a method called {@code name} in {@code source} (overloads included), found by matching braces. */
    private static List<String> bodies(String source, String name) {
        List<String> bodies = new ArrayList<>();
        Matcher declaration = Pattern.compile("(?m)^[ \\t]+(?:(?:public|private|protected|static|final|synchronized)\\s+)*[\\w.<>\\[\\], ?]+\\s+" + name + "\\s*\\([^;{}]*\\)\\s*(?:throws [\\w., ]+)?\\s*\\{").matcher(source);
        while (declaration.find()) {
            int depth = 1;
            int index = declaration.end();
            while (depth > 0 && index < source.length()) {
                char each = source.charAt(index++);
                if (each == '{') depth++;
                else if (each == '}') depth--;
            }
            bodies.add(source.substring(declaration.end(), index));
        }
        return bodies;
    }

    @Test
    void everyWriterOfTheFixtureExistsAndCallsTheRecorderForItsKeyInsideTheNamedMethod() {
        for (JsonNode milestone : MilestoneFixture.REGISTRY.path("milestones")) {
            String key = milestone.path("key").stringValue();
            Map<String, List<String>> writers = writers(milestone);
            writers.forEach((type, methods) -> {
                String source = source(type);
                for (String method : methods) {
                    List<String> bodies = bodies(source, method);
                    assertThat(bodies).as("%s names %s.%s, which must be declared", key, type, method).isNotEmpty();
                    // the rhythm's two completing writers call WeeklyRhythm, which reaches the key
                    String call = RHYTHM_KEY.equals(key) ? "rhythm.sessionCompleted(" : SESSION_KEYS.contains(key) ? ".sessionCompleted(" : "MilestoneKey." + key;
                    // a method may be overloaded; one of its bodies is the writer
                    assertThat(bodies).as("%s.%s must call the recorder for %s in its own body", type, method, key).anyMatch(body -> body.contains(call));
                }
                if (!SESSION_KEYS.contains(key)) assertThat(source).as("%s holds the recorder", type).contains(RHYTHM_KEY.equals(key) ? "WeeklyRhythm" : "MilestoneRecorder");
            });
            if (SESSION_KEYS.contains(key)) assertThat(source("MilestoneRecorder")).contains("MilestoneKey." + key);
            if (RHYTHM_KEY.equals(key)) {
                assertThat(source("WeeklyRhythm")).as("WeeklyRhythm reaches the key").contains("MilestoneKey." + key);
                // the third writer, the route that changes the target, goes through the same class
                assertThat(bodies(source("OnboardingService"), "applyPreferences")).as("PUT /preferences checks the current week").anyMatch(body -> body.contains("rhythm.targetChanged("));
                assertThat(MilestoneFixture.routes(milestone)).as("the routes the fixture names").containsExactly("PUT /onboarding/preferences");
            }
            if (writers.isEmpty()) assertThat(milestone.path("writer").isNull()).as("%s has no writer in registry version 1", key).isTrue();
        }
    }

    @Test
    void theScanTellsTheMethodsOfOneClassApart() {
        String capture = source("CaptureService");
        for (String writer : List.of("create", "convert", "archive")) {
            assertThat(bodies(capture, writer)).as(writer).anyMatch(body -> body.contains("MilestoneKey.CAPTURE_NOTE"));
        }
        // a method that is not a writer of the note is not credited with the writers' calls, so a call deleted from `create` could not hide behind `archive`
        for (String other : List.of("update", "delete", "read", "list")) {
            assertThat(bodies(capture, other)).as(other).isNotEmpty().noneMatch(body -> body.contains("MilestoneKey."));
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
        Set<String> rhythmCallers = new TreeSet<>();
        for (Map.Entry<String, Path> entry : CLASSES.entrySet()) {
            // the registry, the recorder and the code of the path itself are the onboarding package
            if (entry.getValue().toString().contains("/learning/onboarding/")) continue;
            String source = source(entry.getKey());
            Matcher keys = KEY.matcher(source);
            while (keys.find()) {
                String key = keys.group(1);
                if (!declared.containsKey(key) || !declared.get(key).contains(entry.getKey())) undeclared.add(entry.getKey() + " writes " + key);
            }
            if (source.contains("milestones.sessionCompleted(")) sessionCallers.add(entry.getKey());
            if (source.contains("rhythm.sessionCompleted(")) rhythmCallers.add(entry.getKey());
        }
        assertThat(undeclared).as("a class that records a milestone must be the writer named in contracts/onboarding/milestones.json").isEmpty();
        assertThat(sessionCallers).containsExactlyInAnyOrderElementsOf(declared.get("FIRST_SESSION"));
        assertThat(declared.get("FIRST_SESSION")).isEqualTo(declared.get("TWO_DAYS"));
        // the weekly rhythm is called by the same two completing writers, right after the milestones
        assertThat(rhythmCallers).containsExactlyInAnyOrderElementsOf(declared.get(RHYTHM_KEY));
        assertThat(declared.get(RHYTHM_KEY)).isEqualTo(declared.get("FIRST_SESSION"));
    }
}

package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApkgImportParserDeckFixturesTest {

    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOUND_PATTERN = Pattern.compile("\\[sound:([^\\]]+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_SRC_PATTERN = Pattern.compile("<audio[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_SRC_PATTERN = Pattern.compile("<video[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    @Test
    void parsesAllProvidedApkgFixtures() throws Exception {
        Path fixturesDir = locateFixturesDir();
        Assumptions.assumeTrue(fixturesDir != null && Files.isDirectory(fixturesDir), "test_decks directory is not available");

        List<Path> apkgFiles = Files.list(fixturesDir)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".apkg"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();

        Assumptions.assumeFalse(apkgFiles.isEmpty(), "No .apkg files in test_decks");

        ApkgImportParser parser = new ApkgImportParser(new ObjectMapper());
        for (Path apkg : apkgFiles) {
            try (InputStream in = Files.newInputStream(apkg)) {
                ImportPreview preview = parser.preview(in, 2);
                assertNotNull(preview, "preview should be available for " + apkg.getFileName());
                assertFalse(preview.fields().isEmpty(), "fields should not be empty for " + apkg.getFileName());
            }

            try (InputStream in = Files.newInputStream(apkg);
                 ApkgImportParser.ApkgImportStream stream = parser.openStream(in)) {
                assertFalse(stream.fields().isEmpty(), "stream fields should not be empty for " + apkg.getFileName());
                assertTrue(stream.hasNext(), "stream must contain at least one note for " + apkg.getFileName());

                int processed = 0;
                List<String> mediaRefs = new ArrayList<>();
                while (stream.hasNext() && processed < 8) {
                    ImportRecord record = stream.next();
                    if (record == null) {
                        continue;
                    }
                    processed++;
                    collectMediaReferences(record, mediaRefs);
                }
                assertTrue(processed > 0, "at least one note should be read for " + apkg.getFileName());

                int checkedMedia = 0;
                for (String ref : mediaRefs) {
                    if (checkedMedia >= 8) {
                        break;
                    }
                    if (ref == null || ref.isBlank() || ref.startsWith("http://") || ref.startsWith("https://")) {
                        continue;
                    }
                    checkedMedia++;
                    ImportMedia media = stream.openMedia(ref);
                    if (media != null) {
                        try (InputStream mediaStream = media.stream()) {
                            assertTrue(media.size() >= 0, "media size should be non-negative for " + ref);
                            mediaStream.readNBytes(1);
                        }
                    }
                }
            }
        }
    }

    private void collectMediaReferences(ImportRecord record, List<String> refs) {
        if (record == null || record.fields() == null) {
            return;
        }
        for (String value : record.fields().values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            collectByPattern(value, IMG_SRC_PATTERN, refs);
            collectByPattern(value, SOUND_PATTERN, refs);
            collectByPattern(value, AUDIO_SRC_PATTERN, refs);
            collectByPattern(value, VIDEO_SRC_PATTERN, refs);
        }
    }

    private void collectByPattern(String value, Pattern pattern, List<String> refs) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String ref = matcher.group(1);
            if (ref != null && !ref.isBlank()) {
                refs.add(ref.trim());
            }
        }
    }

    private Path locateFixturesDir() {
        String fromProperty = System.getProperty("mnema.testDecksDir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            Path path = Path.of(fromProperty).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        List<Path> candidates = List.of(
                Path.of("/home/mattoyudzuru/IdeaProjects/Mnema/test_decks"),
                Path.of("../../test_decks"),
                Path.of("../../../test_decks"),
                Path.of("../../../../test_decks")
        );
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                return normalized;
            }
        }
        return null;
    }
}

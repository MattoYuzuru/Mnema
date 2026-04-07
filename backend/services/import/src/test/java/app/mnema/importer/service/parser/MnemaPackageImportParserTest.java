package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MnemaPackageImportParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesGeneratedMnemaPackageWithManifestAndMedia() throws Exception {
        Path zip = createPackageFixture(true);
        MnemaPackageImportParser parser = new MnemaPackageImportParser(objectMapper);

        try (InputStream in = Files.newInputStream(zip)) {
            ImportPreview preview = parser.preview(in, 2);
            assertEquals(List.of("Front", "Back"), preview.fields());
            assertEquals(1, preview.sample().size());
        }

        try (InputStream in = Files.newInputStream(zip);
             MnemaPackageImportParser.MnemaPackageImportStream stream = parser.openStream(in)) {
            assertEquals(List.of("Front", "Back"), stream.fields());
            assertTrue(stream.isAnki());
            assertNotNull(stream.layout());
            assertEquals(2, stream.templateFields().size());
            assertTrue(stream.hasNext());

            ImportRecord first = stream.next();
            assertEquals("Question", first.fields().get("Front"));
            assertEquals("Answer", first.fields().get("Back"));
            assertEquals(3, first.orderIndex());
            assertNotNull(first.ankiTemplate());
            assertEquals("<div>{{Front}}</div>", first.ankiTemplate().frontTemplate());

            ImportMedia byId = stream.openMedia("mnema-media://img-1");
            assertNotNull(byId);
            try (InputStream mediaStream = byId.stream()) {
                assertEquals("png", new String(mediaStream.readAllBytes(), StandardCharsets.UTF_8));
            }

            ImportMedia byName = stream.openMedia("sample.png");
            assertNotNull(byName);
            ImportMedia missing = stream.openMedia("missing.png");
            assertNull(missing);
            assertFalse(stream.hasNext());
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void failsWhenDeckCsvIsMissing() throws Exception {
        Path zip = createPackageFixture(false);
        MnemaPackageImportParser parser = new MnemaPackageImportParser(objectMapper);

        try (InputStream in = Files.newInputStream(zip)) {
            IOException ex = assertThrows(IOException.class, () -> parser.openStream(in));
            assertTrue(ex.getMessage().contains("Missing deck.csv"));
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private Path createPackageFixture(boolean includeCsv) throws Exception {
        Path zip = Files.createTempFile("mnema-package-test-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            if (includeCsv) {
                writeEntry(out, "deck.csv",
                        "Front,Back,__order,__anki_front,__anki_back,__anki_css,__anki_modelId,__anki_modelName,__anki_templateName\n" +
                                "Question,Answer,3,<div>{{Front}}</div>,<div>{{Back}}</div>,.card { color: black; },basic-id,Basic,Card 1\n");
            }
            writeEntry(out, "deck.json", objectMapper.writeValueAsString(manifest()));
            writeEntry(out, "media.json", objectMapper.writeValueAsString(Map.of(
                    "img-1", Map.of("fileName", "sample.png")
            )));
            writeEntry(out, "media/sample.png", "png");
        }
        return zip;
    }

    private MnemaPackageManifest manifest() {
        ObjectNode layout = objectMapper.createObjectNode();
        layout.putArray("front").add("Front");
        layout.putArray("back").add("Back");
        return new MnemaPackageManifest(
                "mnema",
                1,
                new MnemaPackageManifest.DeckMeta("Demo", "Demo deck", "en", new String[]{"demo"}),
                new MnemaPackageManifest.TemplateMeta(
                        "Basic",
                        "Basic template",
                        layout,
                        true,
                        List.of(
                                new MnemaPackageManifest.FieldMeta("Front", "Front", "text", true, true, 0, null, null),
                                new MnemaPackageManifest.FieldMeta("Back", "Back", "text", true, false, 1, null, null)
                        )
                )
        );
    }

    private void writeEntry(ZipOutputStream out, String name, String value) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}

package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MnpkgImportParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsManifestCardsAndMediaFromSqlitePackage() throws Exception {
        Path sqliteFile = createFixturePackage();
        MnpkgImportParser parser = new MnpkgImportParser(objectMapper);

        try (InputStream in = Files.newInputStream(sqliteFile)) {
            ImportPreview preview = parser.preview(in, 2);
            assertEquals(2, preview.fields().size());
            assertEquals("Front", preview.fields().get(0));
            assertEquals("Back", preview.fields().get(1));
            assertEquals(1, preview.sample().size());
        }

        try (InputStream in = Files.newInputStream(sqliteFile);
             MnpkgImportParser.MnpkgImportStream stream = parser.openStream(in)) {
            assertTrue(stream.isAnki());
            assertTrue(stream.hasNext());

            ImportRecord record = stream.next();
            assertNotNull(record);
            assertEquals("hello", record.fields().get("Front"));
            assertEquals("world", record.fields().get("Back"));
            assertNotNull(record.ankiTemplate());
            assertEquals("<div>{{Front}}</div>", record.ankiTemplate().frontTemplate());

            ImportMedia mediaById = stream.openMedia("mnema-media://11111111-1111-1111-1111-111111111111");
            assertNotNull(mediaById);
            try (InputStream mediaStream = mediaById.stream()) {
                byte[] bytes = mediaStream.readAllBytes();
                assertEquals("png", new String(bytes, StandardCharsets.UTF_8));
            }

            ImportMedia mediaByName = stream.openMedia("sample.png");
            assertNotNull(mediaByName);
        } finally {
            Files.deleteIfExists(sqliteFile);
        }
    }

    private Path createFixturePackage() throws Exception {
        Path sqliteFile = Files.createTempFile("mnpkg-parser-test-", ".mnpkg");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "create table manifest (manifest_json text not null)"
            )) {
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "create table cards (" +
                            "row_index integer primary key autoincrement," +
                            "order_index integer," +
                            "fields_json text not null," +
                            "anki_json text" +
                            ")"
            )) {
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "create table media (" +
                            "media_id text primary key," +
                            "file_name text not null," +
                            "kind text," +
                            "mime_type text," +
                            "size_bytes integer," +
                            "payload blob not null" +
                            ")"
            )) {
                statement.execute();
            }

            ObjectNode layout = objectMapper.createObjectNode();
            layout.putArray("front").add("Front");
            layout.putArray("back").add("Back");
            MnemaPackageManifest manifest = new MnemaPackageManifest(
                    "mnema",
                    1,
                    new MnemaPackageManifest.DeckMeta("Demo", "Demo deck", "en", new String[]{"demo"}),
                    new MnemaPackageManifest.TemplateMeta(
                            "Demo template",
                            "Demo template description",
                            layout,
                            true,
                            java.util.List.of(
                                    new MnemaPackageManifest.FieldMeta("Front", "Front", "text", true, true, 0, null, null),
                                    new MnemaPackageManifest.FieldMeta("Back", "Back", "text", true, false, 1, null, null)
                            )
                    )
            );
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into manifest (manifest_json) values (?)"
            )) {
                statement.setString(1, objectMapper.writeValueAsString(manifest));
                statement.executeUpdate();
            }

            ObjectNode fields = objectMapper.createObjectNode();
            fields.put("Front", "hello");
            fields.put("Back", "world");
            ObjectNode anki = objectMapper.createObjectNode();
            anki.put("front", "<div>{{Front}}</div>");
            anki.put("back", "<div>{{Back}}</div>");
            anki.put("css", ".card{color:black;}");
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into cards (order_index, fields_json, anki_json) values (?, ?, ?)"
            )) {
                statement.setInt(1, 0);
                statement.setString(2, objectMapper.writeValueAsString(fields));
                statement.setString(3, objectMapper.writeValueAsString(anki));
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into media (media_id, file_name, kind, mime_type, size_bytes, payload) values (?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, "11111111-1111-1111-1111-111111111111");
                statement.setString(2, "sample.png");
                statement.setString(3, "card_image");
                statement.setString(4, "image/png");
                statement.setLong(5, 3);
                statement.setBytes(6, "png".getBytes(StandardCharsets.UTF_8));
                statement.executeUpdate();
            }
        }
        return sqliteFile;
    }
}

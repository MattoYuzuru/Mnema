package app.mnema.importer.service.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApkgImportParserDeckFixturesTest {

    private static final String MODEL_ID = "1001";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesGeneratedApkgFixtureWithMedia() throws Exception {
        Path apkg = createApkgFixture();
        ApkgImportParser parser = new ApkgImportParser(objectMapper);

        try (InputStream in = Files.newInputStream(apkg)) {
            ImportPreview preview = parser.preview(in, 2);
            assertNotNull(preview);
            assertEquals(2, preview.fields().size());
            assertEquals("Front", preview.fields().get(0));
            assertEquals("Back", preview.fields().get(1));
            assertFalse(preview.sample().isEmpty());
        }

        try (InputStream in = Files.newInputStream(apkg);
             ApkgImportParser.ApkgImportStream stream = parser.openStream(in)) {
            assertTrue(stream.isAnki());
            assertNotNull(stream.layout());
            assertEquals(2, stream.totalItems());
            assertTrue(stream.hasNext());

            ImportRecord first = stream.next();
            assertNotNull(first);
            assertEquals("Front", stream.fields().get(0));
            assertTrue(first.fields().get("Front").contains("sample.png"));
            assertNotNull(first.progress());
            assertNotNull(first.ankiTemplate());
            assertEquals("<div>{{Front}}</div>", first.ankiTemplate().frontTemplate());

            ImportMedia image = stream.openMedia("sample.png");
            assertNotNull(image);
            try (InputStream mediaStream = image.stream()) {
                assertEquals("png", new String(mediaStream.readAllBytes(), StandardCharsets.UTF_8));
            }

            ImportMedia audio = stream.openMedia("sample.mp3");
            assertNotNull(audio);
            try (InputStream mediaStream = audio.stream()) {
                assertEquals("mp3", new String(mediaStream.readAllBytes(), StandardCharsets.UTF_8));
            }

            assertTrue(stream.hasNext());
            ImportRecord second = stream.next();
            assertNotNull(second);
            assertEquals("Second answer", second.fields().get("Back"));
            assertFalse(stream.hasNext());
        } finally {
            Files.deleteIfExists(apkg);
        }
    }

    private Path createApkgFixture() throws Exception {
        Path collectionFile = createCollectionDatabase();
        Path apkgFile = Files.createTempFile("apkg-parser-test-", ".apkg");

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apkgFile))) {
            zip.putNextEntry(new ZipEntry("collection.anki2"));
            Files.copy(collectionFile, zip);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("media"));
            zip.write(objectMapper.writeValueAsBytes(Map.of("0", "sample.png", "1", "sample.mp3")));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("0"));
            zip.write("png".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("1"));
            zip.write("mp3".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } finally {
            Files.deleteIfExists(collectionFile);
        }

        return apkgFile;
    }

    private Path createCollectionDatabase() throws Exception {
        Path sqliteFile = Files.createTempFile("apkg-collection-", ".anki2");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            createSchema(connection);
            insertModels(connection);
            insertNotes(connection);
            insertCards(connection);
        }

        return sqliteFile;
    }

    private void createSchema(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "create table col (models text not null)"
        )) {
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "create table notes (" +
                        "id integer primary key," +
                        "mid integer not null," +
                        "flds text not null" +
                        ")"
        )) {
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "create table cards (" +
                        "id integer primary key," +
                        "nid integer not null," +
                        "ivl integer not null," +
                        "factor integer not null," +
                        "reps integer not null," +
                        "queue integer not null," +
                        "type integer not null" +
                        ")"
        )) {
            statement.execute();
        }
    }

    private void insertModels(Connection connection) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode model = root.putObject(MODEL_ID);
        model.put("name", "Basic");
        model.put("css", ".card { color: black; }");
        ArrayNode fields = model.putArray("flds");
        fields.addObject().put("name", "Front");
        fields.addObject().put("name", "Back");

        ArrayNode templates = model.putArray("tmpls");
        templates.addObject()
                .put("name", "Card 1")
                .put("ord", 0)
                .put("qfmt", "<div>{{Front}}</div>")
                .put("afmt", "<div>{{FrontSide}}</div><hr><div>{{Back}}</div>");

        try (PreparedStatement statement = connection.prepareStatement(
                "insert into col (models) values (?)"
        )) {
            statement.setString(1, objectMapper.writeValueAsString(root));
            statement.executeUpdate();
        }
    }

    private void insertNotes(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into notes (id, mid, flds) values (?, ?, ?)"
        )) {
            statement.setLong(1, 1L);
            statement.setLong(2, Long.parseLong(MODEL_ID));
            statement.setString(3, "Question <img src=\"sample.png\"> [sound:sample.mp3]\u001fAnswer");
            statement.executeUpdate();

            statement.setLong(1, 2L);
            statement.setLong(2, Long.parseLong(MODEL_ID));
            statement.setString(3, "Second question\u001fSecond answer");
            statement.executeUpdate();
        }
    }

    private void insertCards(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into cards (id, nid, ivl, factor, reps, queue, type) values (?, ?, ?, ?, ?, ?, ?)"
        )) {
            statement.setLong(1, 10L);
            statement.setLong(2, 1L);
            statement.setInt(3, 12);
            statement.setInt(4, 2500);
            statement.setInt(5, 7);
            statement.setInt(6, 2);
            statement.setInt(7, 2);
            statement.executeUpdate();

            statement.setLong(1, 11L);
            statement.setLong(2, 2L);
            statement.setInt(3, 1);
            statement.setInt(4, 2300);
            statement.setInt(5, 1);
            statement.setInt(6, 1);
            statement.setInt(7, 1);
            statement.executeUpdate();
        }
    }
}

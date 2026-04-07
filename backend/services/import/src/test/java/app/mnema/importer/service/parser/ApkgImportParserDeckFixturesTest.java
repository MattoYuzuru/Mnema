package app.mnema.importer.service.parser;

import com.github.luben.zstd.ZstdOutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void parsesAnki21bFixtureWithProtoMediaAndTableMetadata() throws Exception {
        Path apkg = createAnki21bFixtureWithProtoMedia();
        ApkgImportParser parser = new ApkgImportParser(objectMapper);

        try (InputStream in = Files.newInputStream(apkg);
             ApkgImportParser.ApkgImportStream stream = parser.openStream(in)) {
            assertTrue(stream.isAnki());
            assertNotNull(stream.layout());
            assertEquals(java.util.List.of("Front", "Back"), stream.fields());
            assertEquals("Front", stream.layout().front().getFirst());
            assertEquals("Back", stream.layout().back().getFirst());

            ImportRecord record = stream.next();
            assertNotNull(record);
            assertTrue(record.fields().get("Front").contains("sample.png"));
            assertTrue(record.fields().get("Front").contains("sample.mp3"));
            assertEquals("Answer", record.fields().get("Back"));
            assertNotNull(record.ankiTemplate());
            assertEquals("<div>{{Front}}</div>", record.ankiTemplate().frontTemplate());
            assertTrue(record.ankiTemplate().css().contains(".card"));

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
        } finally {
            Files.deleteIfExists(apkg);
        }
    }

    @Test
    void fallsBackToSyntheticFieldNamesWhenModelsAreBroken() throws Exception {
        Path apkg = createFallbackFieldsApkgFixture();
        ApkgImportParser parser = new ApkgImportParser(objectMapper);

        try (InputStream in = Files.newInputStream(apkg);
             ApkgImportParser.ApkgImportStream stream = parser.openStream(in)) {
            assertEquals(java.util.List.of("Field 1", "Field 2"), stream.fields());
            assertFalse(stream.isAnki());
            assertTrue(stream.hasNext());
            ImportRecord record = stream.next();
            assertNotNull(record);
            assertEquals("Question", record.fields().get("Field 1"));
            assertEquals("Answer", record.fields().get("Field 2"));
            assertNull(record.ankiTemplate());
            assertNull(stream.layout());
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

    private Path createAnki21bFixtureWithProtoMedia() throws Exception {
        Path collectionFile = createCollectionDatabaseWithMetadataTables();
        Path compressedCollection = Files.createTempFile("apkg-collection-", ".anki21b");
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ZstdOutputStream zstd = new ZstdOutputStream(buffer);
             InputStream inputStream = Files.newInputStream(collectionFile)) {
            inputStream.transferTo(zstd);
            zstd.close();
            Files.write(compressedCollection, buffer.toByteArray());
        }

        Path apkgFile = Files.createTempFile("apkg-parser-v21b-test-", ".apkg");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apkgFile))) {
            zip.putNextEntry(new ZipEntry("collection.anki21b"));
            Files.copy(compressedCollection, zip);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("media"));
            zip.write(compressZstd(mediaProto(Map.of("0", "sample.png", "1", "sample.mp3"))));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("0"));
            zip.write("png".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("1"));
            zip.write("mp3".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } finally {
            Files.deleteIfExists(collectionFile);
            Files.deleteIfExists(compressedCollection);
        }
        return apkgFile;
    }

    private Path createFallbackFieldsApkgFixture() throws Exception {
        Path sqliteFile = Files.createTempFile("apkg-fallback-fields-", ".anki2");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            createSchema(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into col (models) values (?)"
            )) {
                statement.setString(1, "{");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into notes (id, mid, flds) values (?, ?, ?)"
            )) {
                statement.setLong(1, 1L);
                statement.setLong(2, 999L);
                statement.setString(3, "Question\u001fAnswer");
                statement.executeUpdate();
            }
        }

        Path apkgFile = Files.createTempFile("apkg-fallback-fields-", ".apkg");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apkgFile))) {
            zip.putNextEntry(new ZipEntry("collection.anki2"));
            Files.copy(sqliteFile, zip);
            zip.closeEntry();
        } finally {
            Files.deleteIfExists(sqliteFile);
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

    private Path createCollectionDatabaseWithMetadataTables() throws Exception {
        Path sqliteFile = Files.createTempFile("apkg-collection-meta-", ".anki21");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile.toAbsolutePath())) {
            createSchema(connection);
            createMetadataTables(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into col (models) values (?)"
            )) {
                statement.setString(1, "");
                statement.executeUpdate();
            }
            insertFieldsTable(connection);
            insertNotetypeTable(connection);
            insertTemplatesTable(connection);
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

    private void createMetadataTables(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "create table fields (" +
                        "ntid integer not null," +
                        "ord integer not null," +
                        "name text not null" +
                        ")"
        )) {
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "create table notetypes (" +
                        "id integer not null," +
                        "config blob" +
                        ")"
        )) {
            statement.execute();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "create table templates (" +
                        "ntid integer not null," +
                        "ord integer not null," +
                        "name text not null," +
                        "config blob" +
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

    private void insertFieldsTable(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into fields (ntid, ord, name) values (?, ?, ?)"
        )) {
            statement.setLong(1, Long.parseLong(MODEL_ID));
            statement.setInt(2, 0);
            statement.setString(3, "Front");
            statement.executeUpdate();

            statement.setLong(1, Long.parseLong(MODEL_ID));
            statement.setInt(2, 1);
            statement.setString(3, "Back");
            statement.executeUpdate();
        }
    }

    private void insertNotetypeTable(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into notetypes (id, config) values (?, ?)"
        )) {
            statement.setLong(1, Long.parseLong(MODEL_ID));
            statement.setBytes(2, protoStrings(".card { color: blue; }"));
            statement.executeUpdate();
        }
    }

    private void insertTemplatesTable(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into templates (ntid, ord, name, config) values (?, ?, ?, ?)"
        )) {
            statement.setLong(1, Long.parseLong(MODEL_ID));
            statement.setInt(2, 0);
            statement.setString(3, "Card 1");
            statement.setBytes(4, protoStrings("<div>{{Front}}</div>", "<div>{{FrontSide}}</div><hr><div>{{Back}}</div>"));
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

    private byte[] compressZstd(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(out)) {
            zstd.write(payload);
        }
        return out.toByteArray();
    }

    private byte[] mediaProto(Map<String, String> indexToName) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String index : java.util.List.of("0", "1")) {
            String name = indexToName.get(index);
            if (name == null) {
                continue;
            }
            byte[] inner = protoStrings(name);
            out.write(0x0A);
            writeVarint(out, inner.length);
            out.write(inner);
        }
        return out.toByteArray();
    }

    private byte[] protoStrings(String... values) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.write(0x0A);
            writeVarint(out, bytes.length);
            out.write(bytes);
        }
        return out.toByteArray();
    }

    private void writeVarint(ByteArrayOutputStream out, int value) {
        int current = value;
        while ((current & ~0x7F) != 0) {
            out.write((current & 0x7F) | 0x80);
            current >>>= 7;
        }
        out.write(current);
    }
}

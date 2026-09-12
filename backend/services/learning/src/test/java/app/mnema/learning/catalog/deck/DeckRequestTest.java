package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class DeckRequestTest {
    private static final String COMMAND = "018f1d98-5c10-7abc-8abc-0123456789ab";

    @Test
    void readsSharedFixtureAndPreservesMetadata() throws Exception {
        JsonNode fixtures = JsonMapper.builder().build().readTree(Files.readString(
                Path.of("../../../contracts/decks/metadata.json")));
        DeckCommand command = read(fixtures.path("command").toString());
        assertThat(command.commandId()).isEqualTo(UUID.fromString(COMMAND));
        assertThat(command.metadata()).isEqualTo(fixtures.path("detail").path("metadata"));
        assertThat(command.description()).startsWith("  ").endsWith("  ").contains("\n");
        assertThat(read(fixtures.path("command").toString().replace(COMMAND, COMMAND.toUpperCase())).commandId())
                .isEqualTo(command.commandId());
        UUID target = UUID.randomUUID();
        assertThat(command.envelope(target, 9007199254740993L).path("expectedVersion").textValue()).isEqualTo("9007199254740993");
        assertThat(command.envelope(target, 0L)).isNotEqualTo(command.envelope(UUID.randomUUID(), 0L));
    }

    @Test
    void rejectsAmbiguousOrUnboundedInputWithoutEchoingPrivateValues() {
        for (String invalid : List.of("", "[]", "{}", body("\"private\"", "null"),
                body("0", "\"\""), body("\" \"", "\"\""), body("\"x\\u0000\"", "\"\""),
                body("\"x\\ud800\"", "\"\""), body("\"x\\udc00\"", "\"\""),
                body("\"x\"", "\"\"").replace("\"title\":", "\"title\":\"private\",\"title\":"),
                body("\"x\"", "\"\"").replace("\"metadata\":", "\"unknown\":true,\"metadata\":"),
                body("\"x\"", "\"\"").replace("\"description\":", "\"unknown\":true,\"description\":"),
                body("\"x\"", "\"\"") + " {}", " ".repeat(8193),
                body("\"x\"", "\"\"").replace(COMMAND, "00000000-0000-0000-0000-000000000000"),
                body("\"x\"", "\"\"").replace(COMMAND, "018f1d98-5c10-5abc-8abc-0123456789ab"))) {
            assertThatThrownBy(() -> read(invalid)).isInstanceOf(InvalidRequestException.class)
                    .hasMessage("Invalid request").hasNoCause();
        }
        assertThatThrownBy(() -> DeckCommand.read(new ByteArrayInputStream(new byte[]{(byte) 0xff})))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new DeckCommand(null, "x", "")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new DeckCommand(UUID.randomUUID(), null, "")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new DeckCommand(UUID.randomUUID(), "x", null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void boundsCodePointsUtf8AndRawStreamBeforeDeserialization() {
        assertThat(new DeckCommand(UUID.randomUUID(), "😀".repeat(200), "я".repeat(2048)).title()).hasSize(400);
        assertThatThrownBy(() -> new DeckCommand(UUID.randomUUID(), "😀".repeat(201), ""))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new DeckCommand(UUID.randomUUID(), "x", "я".repeat(2048) + "x"))
                .isInstanceOf(InvalidRequestException.class);
        InputStream infinite = new InputStream() {
            private int count;
            @Override public int read() {
                assertThat(++count).isLessThanOrEqualTo(8193);
                return ' ';
            }
        };
        assertThatThrownBy(() -> DeckCommand.read(infinite)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> DeckCommand.read(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private socket data"); }
        })).isInstanceOf(InvalidRequestException.class).hasNoCause();
        String small = body("\"x\"", "\"\"");
        assertThat(read(small + " ".repeat(8192 - small.length())).title()).isEqualTo("x");
    }

    @Test
    void validatesExactStrongVersionGrammar() {
        assertThat(version("\"0\"")).isZero();
        assertThat(version("\"9223372036854775806\"")).isEqualTo(Long.MAX_VALUE - 1);
        assertThatThrownBy(() -> DeckPrecondition.read(Collections.emptyEnumeration()))
                .isInstanceOf(VersionPreconditionRequiredException.class);
        assertThatThrownBy(() -> DeckPrecondition.read(null)).isInstanceOf(VersionPreconditionRequiredException.class);
        for (String value : List.of("", "0", "*", "W/\"0\"", "\"01\"", "\"-1\"", "\"0\",\"1\"",
                " \"0\"", "\"0\" ", "\"9223372036854775807\"", "\"9999999999999999999\"", "\"1.0\"")) {
            assertThatThrownBy(() -> version(value)).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> DeckPrecondition.read(Collections.enumeration(List.of("\"0\"", "\"0\""))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void cursorRoundTripsMicrosecondsAndRejectsNoncanonicalInputs() {
        DeckCursor cursor = new DeckCursor(Instant.parse("2026-09-12T12:00:00.123456Z"), UUID.randomUUID());
        assertThat(DeckCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(DeckCursor.decode(null)).isNull();
        for (String value : List.of("", " ", "a".repeat(129), "a", "====", cursor.encode() + "=")) {
            assertThatThrownBy(() -> DeckCursor.decode(value)).isInstanceOf(InvalidRequestException.class);
        }
        for (String raw : List.of("no/time/uuid", "private", "bad-date/" + cursor.deckId(),
                "2026-09-12T12:00:00.1234567Z/" + cursor.deckId(), "1960-01-01T00:00:00Z/" + cursor.deckId(),
                "2026-09-12T12:00:00Z/bad-id", "2026-09-12T12:00:00.000Z/" + cursor.deckId(),
                "+10000-01-01T00:00:00Z/" + cursor.deckId())) {
            String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
            assertThatThrownBy(() -> DeckCursor.decode(encoded)).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(DeckCursor.pageSize(null)).isEqualTo(20);
        assertThat(DeckCursor.pageSize("100")).isEqualTo(100);
        for (String value : List.of("", "0", "01", "101", "-1", "2147483648", "x")) {
            assertThatThrownBy(() -> DeckCursor.pageSize(value)).isInstanceOf(InvalidRequestException.class);
        }
        for (String value : List.of("1-1-4-8-1", "00000000-0000-0000-0000-000000000000", "x".repeat(36))) {
            assertThatThrownBy(() -> DeckCommand.entityId(value)).isInstanceOf(InvalidRequestException.class);
        }
    }

    private static long version(String value) { return DeckPrecondition.read(Collections.enumeration(List.of(value))); }
    private static DeckCommand read(String value) { return DeckCommand.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); }
    private static String body(String title, String description) {
        return "{\"commandId\":\"" + COMMAND + "\",\"metadata\":{\"title\":" + title + ",\"description\":" + description + "}}";
    }
}

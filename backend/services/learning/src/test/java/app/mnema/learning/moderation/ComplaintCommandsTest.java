package app.mnema.learning.moderation;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplaintCommandsTest {
    private static InputStream body(String json) { return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)); }

    @Test
    void aFilingIsAClosedReasonAndAnOptionalTrimmedText() {
        assertThat(ComplaintCommands.filing(body("{\"reason\":\"COPYRIGHT\"}"))).isEqualTo(new ComplaintCommands.Filing("COPYRIGHT", null));
        assertThat(ComplaintCommands.filing(body("{\"reason\":\"OTHER\",\"text\":\"  два слова \\n\"}"))).isEqualTo(new ComplaintCommands.Filing("OTHER", "два слова"));
        assertThat(ComplaintCommands.filing(body("{\"text\":\"\\t \",\"reason\":\"SPAM\"}")).text()).isNull();
        assertThat(ComplaintCommands.clean("a\tb\nc")).isEqualTo("a\tb\nc");
        for (String reason : new String[] {"COPYRIGHT", "PERSONAL_DATA", "ILLEGAL", "SPAM", "OTHER"}) {
            assertThat(ComplaintCommands.filing(body("{\"reason\":\"" + reason + "\"}")).reason()).isEqualTo(reason);
        }
        String thousand = "😀".repeat(1000);
        assertThat(ComplaintCommands.filing(body("{\"reason\":\"SPAM\",\"text\":\"" + thousand + "\"}")).text()).isEqualTo(thousand);
        for (String bad : new String[] {"{\"reason\":\"SPAM\",\"text\":\"" + thousand + "x\"}", "{\"reason\":\"SPAM\",\"text\":\"a\\u001fb\"}", "{\"reason\":\"SPAM\",\"text\":true}",
                "{\"reason\":null}", "{\"reason\":\"spam\"}", "{\"reason\":\"SPAM\",\"x\":1}", "{}", "null", "[]", "", "{\"reason\":\"SPAM\"} {}", "{\"reason\":\"SPAM\","}) {
            assertThatThrownBy(() -> ComplaintCommands.filing(body(bad))).as(bad.length() > 60 ? bad.substring(0, 60) : bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void aDecisionNamesItsUnitsOnlyForATakedown() {
        UUID key = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        assertThat(ComplaintCommands.decision(body("{\"decision\":\"HIDDEN\"}"))).isEqualTo(new ComplaintCommands.Decision("HIDDEN", java.util.List.of(), false));
        ComplaintCommands.Decision down = ComplaintCommands.decision(body("{\"decision\":\"TAKEN_DOWN\",\"hide\":true,\"units\":[{\"kind\":\"ITEM\",\"key\":\"" + key
                + "\",\"revisionId\":null},{\"kind\":\"EXERCISE\",\"key\":\"" + key + "\",\"revisionId\":\"" + revision + "\"},{\"kind\":\"ITEM\",\"key\":\"" + key + "\"}]}"));
        assertThat(down.hide()).isTrue();
        assertThat(down.units()).as("the same unit twice is one").containsExactly(new ComplaintCommands.UnitSpec("ITEM", key, null),
                new ComplaintCommands.UnitSpec("EXERCISE", key, revision));
        assertThat(ComplaintCommands.decision(body("{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"kind\":\"ITEM\",\"key\":\"" + key + "\"}]}")).hide()).isFalse();
        String unit = "{\"kind\":\"ITEM\",\"key\":\"" + key + "\"}";
        for (String bad : new String[] {"{\"decision\":\"TAKEN_DOWN\"}", "{\"decision\":\"TAKEN_DOWN\",\"units\":[]}", "{\"decision\":\"TAKEN_DOWN\",\"units\":{}}",
                "{\"decision\":\"TAKEN_DOWN\",\"units\":[" + unit + "],\"hide\":\"yes\"}", "{\"decision\":\"TAKEN_DOWN\",\"units\":[" + unit + "],\"x\":1}",
                "{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"kind\":\"ITEM\"}]}", "{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"key\":\"" + key + "\"}]}",
                "{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"kind\":\"DECK\",\"key\":\"" + key + "\"}]}", "{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"kind\":\"ITEM\",\"key\":\"x\"}]}",
                "{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"kind\":\"ITEM\",\"key\":\"" + key.toString().toUpperCase(java.util.Locale.ROOT) + "\"}]}",
                "{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"kind\":\"ITEM\",\"key\":\"" + key + "\",\"revisionId\":5}]}",
                "{\"decision\":\"TAKEN_DOWN\",\"units\":[{\"kind\":\"ITEM\",\"key\":\"" + key + "\",\"extra\":1}]}", "{\"decision\":\"TAKEN_DOWN\",\"units\":[\"x\"]}",
                "{\"decision\":\"HIDDEN\",\"units\":[]}", "{\"decision\":\"REJECTED\",\"hide\":false}", "{\"decision\":\"NONE\"}", "{\"decision\":3}", "{}", "{\"units\":[]}"}) {
            assertThatThrownBy(() -> ComplaintCommands.decision(body(bad))).as(bad).isInstanceOf(InvalidRequestException.class);
        }
        StringBuilder many = new StringBuilder("{\"decision\":\"TAKEN_DOWN\",\"units\":[");
        for (int index = 0; index < ComplaintCommands.MAX_UNITS + 1; index++) many.append(index == 0 ? "" : ",").append("{\"kind\":\"ITEM\",\"key\":\"").append(UUID.randomUUID()).append("\"}");
        many.append("]}");
        assertThatThrownBy(() -> ComplaintCommands.decision(body(many.toString()))).isInstanceOf(InvalidRequestException.class);
        many.setLength(many.lastIndexOf(",{"));
        many.append("]}");
        assertThat(ComplaintCommands.decision(body(many.toString())).units()).hasSize(ComplaintCommands.MAX_UNITS);
    }

    @Test
    void theQueueCursorIsOpaqueStrictAndRoundTrips() {
        ComplaintCursor cursor = new ComplaintCursor(Instant.parse("2026-10-12T09:30:00.123456Z"), UUID.randomUUID());
        assertThat(ComplaintCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(ComplaintCursor.decode(null)).isNull();
        for (String bad : new String[] {"", "!", "AAAA", "a".repeat(200), java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("1.2/x".getBytes(StandardCharsets.US_ASCII)),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("1.2/" + UUID.randomUUID() + "/x").getBytes(StandardCharsets.US_ASCII)),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("01.2/" + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII)),
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("1.9999999999/" + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII))}) {
            assertThatThrownBy(() -> ComplaintCursor.decode(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(ComplaintCursor.pageSize(null)).isEqualTo(50);
        assertThat(ComplaintCursor.pageSize("1")).isEqualTo(1);
        assertThat(ComplaintCursor.pageSize("100")).isEqualTo(100);
        for (String bad : new String[] {"0", "101", "-1", "x", "", "007", "1.5"}) {
            assertThatThrownBy(() -> ComplaintCursor.pageSize(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }
}

package app.mnema.learning.events;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ambiguous transport values must be rejected before any JDBC operation or receipt lookup. */
class EventRequestsTest {
    @Test
    void cursorRequiresCanonicalDateAndUuidAndRoundTripsAsAnOpaqueKeysetLocation() {
        UUID id = UUID.fromString("aaaa0000-0000-4000-8000-000000000001");
        var cursor = new EventRequests.Cursor(LocalDate.of(2026, 10, 7), id);
        assertThat(EventRequests.cursor(cursor.encode())).isEqualTo(cursor);
        assertThat(EventRequests.cursor(null)).isNull();
        for (String decoded : List.of("2026-10-07", "2026-10-07/" + id + "/extra", "2026-02-30/" + id,
                "2026-10-07/" + id.toString().toUpperCase(), "2026-10-07/bad", "0000-01-01/" + id)) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.getBytes(StandardCharsets.US_ASCII));
            assertThatThrownBy(() -> EventRequests.cursor(encoded)).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> EventRequests.cursor(cursor.encode() + "=")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> EventRequests.cursor("A")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> EventRequests.commandId("aaaa0000-0000-1000-8000-000000000001"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> EventRequests.entityId("00000000-0000-0000-0000-000000000000"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aSingleStrongVersionAndSingleKnownQueryParameterAreRequired() {
        assertThat(EventRequests.version(Collections.enumeration(List.of("\"0\"")))).isZero();
        assertThatThrownBy(() -> EventRequests.version(Collections.enumeration(List.of("\"0\"", "\"1\""))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> EventRequests.version(Collections.emptyEnumeration()))
                .isInstanceOf(VersionPreconditionRequiredException.class);
        assertThatThrownBy(() -> EventRequests.version(null)).isInstanceOf(VersionPreconditionRequiredException.class);
        var request = new MockHttpServletRequest();
        assertThat(EventRequests.parameter(request, "cursor")).isNull();
        request.addParameter("cursor", "one", "two");
        assertThatThrownBy(() -> EventRequests.parameter(request, "cursor")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void bodyReadFailuresMalformedUnicodeControlsAndNonStringFieldsAreInvalidRequests() {
        InputStream failedRead = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private transport detail"); }
        };
        assertThatThrownBy(() -> EventRequests.command(failedRead)).isInstanceOf(InvalidRequestException.class)
                .hasMessage("Invalid request").hasNoCause();
        String valid = "{\"commandId\":\"aaaa0000-0000-4000-8000-000000000001\",\"title\":\"Title\","
                + "\"bodyMarkdown\":\"Body\",\"eventDate\":\"2026-10-07\",\"published\":false}";
        for (String invalid : List.of(valid.replace("\"Title\"", "42"), valid.replace("Title", "Title\\n"),
                valid.replace("Body", "Body\\u0001"), valid.replace("Body", "Body\\ud800"))) {
            assertThatThrownBy(() -> EventRequests.command(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> EventRequests.command(new ByteArrayInputStream(new byte[] {(byte) 0xc3, (byte) 0x28})))
                .isInstanceOf(InvalidRequestException.class);
    }
}

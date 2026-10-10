package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicCursorTest {
    private static final UUID REVISION = UUID.fromString("0198a6c0-1d3e-7a52-9b0c-2f6d8e4a1c35");

    @Test
    void aCursorRoundTripsAndIsBoundToItsKind() {
        String encoded = new PublicCursor('i', REVISION, 40).encode();
        assertThat(PublicCursor.decode(encoded, 'i')).isEqualTo(new PublicCursor('i', REVISION, 40));
        assertThat(PublicCursor.decode(null, 'i')).isNull();
        assertThatThrownBy(() -> PublicCursor.decode(encoded, 'e')).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void malformedCursorsAreRefusedWithoutEchoingTheInput() {
        for (String value : new String[] {"", "!!", "a".repeat(97), enc("i/not-a-uuid/3"), enc("i/" + REVISION + "/03"), enc("i/" + REVISION + "/-1"),
                enc("i/" + REVISION + "/1234567"), enc("x/" + REVISION + "/3/4"), enc("ii/" + REVISION + "/3"),
                enc("i/00000000-0000-0000-0000-000000000000/3"), Base64.getUrlEncoder().encodeToString(("i/" + REVISION + "/3").getBytes(StandardCharsets.US_ASCII))}) {
            assertThatThrownBy(() -> PublicCursor.decode(value, 'i')).as(value).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void thePageSizeDefaultsToTwentyAndNeverExceedsOneHundred() {
        assertThat(PublicCursor.pageSize(null)).isEqualTo(20);
        assertThat(PublicCursor.pageSize("1")).isEqualTo(1);
        assertThat(PublicCursor.pageSize("100")).isEqualTo(100);
        for (String value : new String[] {"0", "101", "-1", "01", "x", "", "1000"}) {
            assertThatThrownBy(() -> PublicCursor.pageSize(value)).as(value).isInstanceOf(InvalidRequestException.class);
        }
    }

    private static String enc(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }
}

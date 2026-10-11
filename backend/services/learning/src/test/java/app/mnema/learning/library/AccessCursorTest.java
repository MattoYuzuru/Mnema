package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessCursorTest {
    @Test
    void aCursorRoundTripsWithMicrosecondPrecision() {
        AccessCursor cursor = new AccessCursor(Instant.parse("2026-10-11T09:30:00.123456Z"), UUID.fromString("018f1d98-5c10-7abc-8abc-0123456789ab"));
        assertThat(AccessCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(AccessCursor.decode(null)).isNull();
    }

    @Test
    void anythingElseIsTheOpaque400() {
        for (String bad : new String[] {"", "!!", "e30", "MS8x", "x".repeat(200), "MS8wMThmMWQ5OC01YzEwLTdhYmMtOGFiYy0wMTIzNDU2Nzg5YWI="}) {
            assertThatThrownBy(() -> AccessCursor.decode(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void thePageSizeIsOneToHundredDefaultTwenty() {
        assertThat(AccessCursor.pageSize(null)).isEqualTo(20);
        assertThat(AccessCursor.pageSize("1")).isEqualTo(1);
        assertThat(AccessCursor.pageSize("100")).isEqualTo(100);
        for (String bad : new String[] {"0", "101", "-1", "01", "x", "1.5", ""}) {
            assertThatThrownBy(() -> AccessCursor.pageSize(bad)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }
}

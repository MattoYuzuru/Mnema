package app.mnema.learning.platform.id;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UuidPolicyTest {

    @Test
    void createsPortableVersionFourIdentifiers() {
        UUID id = UuidPolicy.newPortableId();

        assertThat(id.version()).isEqualTo(4);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void acceptsRfcEntityIdsAndVersionFourOrSevenCommands() {
        UUID versionFour = UUID.randomUUID();
        UUID versionSeven = UUID.fromString("019535d9-3df7-79fb-b466-fa907fa17f9e");
        UUID versionOne = UUID.fromString("00000000-0000-1000-8000-000000000001");

        assertThat(UuidPolicy.requireEntityId(versionOne, "entityId")).isEqualTo(versionOne);
        assertThat(UuidPolicy.requireCommandId(versionFour)).isEqualTo(versionFour);
        assertThat(UuidPolicy.requireCommandId(versionSeven)).isEqualTo(versionSeven);
    }

    @Test
    void rejectsNullNilNonIetfReservedAndUnsupportedCommandVersions() {
        assertThatThrownBy(() -> UuidPolicy.requireEntityId(null, "entityId"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> UuidPolicy.requireEntityId(new UUID(0, 0), "entityId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nil");
        assertThatThrownBy(() -> UuidPolicy.requireEntityId(new UUID(0x1000, 1), "entityId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variant");
        assertThatThrownBy(() -> UuidPolicy.requireEntityId(
                UUID.fromString("00000000-0000-0000-8000-000000000001"), "entityId"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered UUID version");
        assertThatThrownBy(() -> UuidPolicy.requireEntityId(
                UUID.fromString("00000000-0000-9000-8000-000000000001"), "entityId"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered UUID version");
        assertThatThrownBy(() -> UuidPolicy.requireEntityId(
                UUID.fromString("00000000-0000-f000-8000-000000000001"), "entityId"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered UUID version");
        assertThatThrownBy(() -> UuidPolicy.requireCommandId(
                UUID.fromString("00000000-0000-1000-8000-000000000001")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UUIDv4 or UUIDv7");
    }
}

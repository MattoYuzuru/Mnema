package app.mnema.learning.platform.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandIdentityTest {

    @Test
    void acceptsBoundedCanonicalNames() {
        var identity = new CommandIdentity(UUID.randomUUID(), UUID.randomUUID(), "catalog.deck-1", "publish.v1");

        assertThat(identity.scope()).isEqualTo("catalog.deck-1");
        assertThat(identity.type()).isEqualTo("publish.v1");
    }

    @Test
    void rejectsBlankUppercaseMalformedAndOversizedNames() {
        UUID commandId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> new CommandIdentity(commandId, actorId, "", "publish"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandIdentity(commandId, actorId, "Catalog", "publish"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandIdentity(commandId, actorId, "catalog..deck", "publish"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommandIdentity(commandId, actorId, "a".repeat(81), "publish"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

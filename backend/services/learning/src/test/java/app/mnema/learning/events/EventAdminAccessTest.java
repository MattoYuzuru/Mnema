package app.mnema.learning.events;

import app.mnema.learning.platform.api.AccessForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventAdminAccessTest {
    @Test
    void unconfiguredOwnerDeniesEveryAccount() {
        var access = new EventAdminAccess("");
        assertThatThrownBy(() -> access.require(UUID.randomUUID())).isInstanceOf(AccessForbiddenException.class);
        assertThatThrownBy(() -> access.require(null)).isInstanceOf(AccessForbiddenException.class);
    }

    @Test
    void malformedConfigurationFailsAtStartupAndOnlyTheExactOwnerIsAllowed() {
        UUID owner = UUID.fromString("aaaa0000-0000-4000-8000-000000000001");
        var access = new EventAdminAccess(owner.toString());
        assertThatCode(() -> access.require(owner)).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.require(UUID.randomUUID())).isInstanceOf(AccessForbiddenException.class);
        for (String invalid : new String[] {" ", "@Keyko_Mi", owner.toString().toUpperCase(), "00000000-0000-0000-0000-000000000000"}) {
            assertThatThrownBy(() -> new EventAdminAccess(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

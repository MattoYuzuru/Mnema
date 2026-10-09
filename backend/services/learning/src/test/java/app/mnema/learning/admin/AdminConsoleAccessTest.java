package app.mnema.learning.admin;

import app.mnema.learning.platform.api.AccessForbiddenException;
import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminConsoleAccessTest {
    @Test void ownerConfigurationDeniesEmptyAndOtherIdentityAndRejectsMalformedIds() {
        UUID owner = UUID.randomUUID();
        new AdminConsoleAccess(owner.toString()).require(owner);
        assertThatThrownBy(() -> new AdminConsoleAccess("").require(owner)).isInstanceOf(AccessForbiddenException.class);
        assertThatThrownBy(() -> new AdminConsoleAccess(owner.toString()).require(UUID.randomUUID())).isInstanceOf(AccessForbiddenException.class);
        for (String value : new String[]{"bad", "00000000-0000-0000-0000-000000000000", owner.toString().toUpperCase(java.util.Locale.ROOT)})
            assertThatThrownBy(() -> new AdminConsoleAccess(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rangeIsCanonicalBoundedHalfOpenAndIncludesToday() {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        var range = AdminReportRange.parse("2026-10-01", "2026-10-10", now);
        assertThat(range.start()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(range.end()).isEqualTo(Instant.parse("2026-10-10T00:00:00Z"));
        for (String[] pair : new String[][]{{null,"2026-10-10"},{"2026-10-01",null},{"2026-10-01","2026-10-01"},{"2026-01-01","2026-10-01"},
                {"2026-10-01","2026-10-11"},{"2026-02-30","2026-03-01"},{"2026-1-01","2026-01-02"},
                {"0000-12-31","0001-01-01"},{"0000-01-01","0000-01-02"}})
            assertThatThrownBy(() -> AdminReportRange.parse(pair[0],pair[1],now)).isInstanceOf(InvalidRequestException.class);
        var request = new MockHttpServletRequest();
        request.addParameter("from", "a", "b");
        assertThatThrownBy(() -> AdminReportRange.query(request,Set.of("from"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AdminReportRange.query(request,Set.of())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AdminAudit.cursor("bad")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> AdminAudit.cursor(ownerUpper())).isInstanceOf(InvalidRequestException.class);
        assertThat(AdminAudit.cursor(null)).isNull();
        UUID id=UUID.randomUUID(); assertThat(AdminAudit.cursor(id.toString())).isEqualTo(id);
    }
    private static String ownerUpper() { return "ABCDEFAB-CDEF-4000-8000-ABCDEFABCDEF"; }
}

package app.mnema.learning.admin.support;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportRequestsTest {
    private static final UUID ACTOR = UUID.randomUUID();

    @Test void actorIsServerDerivedAndCommandsRequireExactShape() {
        var command = SupportRequests.command(body("reply", "\"text\":\"Answer\""), ACTOR);
        assertThat(command.path("actorAccountId").stringValue()).isEqualTo(ACTOR.toString());
        assertThat(SupportRequests.command(body("status", "\"status\":\"working\""), ACTOR).path("status").stringValue()).isEqualTo("working");
        assertThat(SupportRequests.command(body("note", "\"text\":\"Private note\""), ACTOR).path("text").stringValue()).isEqualTo("Private note");
        for (String extra : new String[]{"\"text\":\"Answer\",\"actorAccountId\":\""+ACTOR+"\"",
                "\"text\":\"Answer\",\"text\":\"Different\"", "\"text\":true", "\"text\":\" \"", "\"text\":[]"}) {
            assertThatThrownBy(() -> SupportRequests.command(body("reply", extra), ACTOR)).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> SupportRequests.command(body("status", "\"status\":\"draft\""), ACTOR)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> SupportRequests.command(body("reply", "\"text\":\""+"a".repeat(3501)+"\""), ACTOR)).isInstanceOf(InvalidRequestException.class);
    }

    @Test void filtersRejectDuplicatesUnknownKeysAndEncodedRoutingInputs() {
        var request = new MockHttpServletRequest();
        request.addParameter("q", "Имя & email");
        request.addParameter("status", "working");
        String query = SupportRequests.query(request, false);
        assertThat(query).contains("q=%D0%98%D0%BC%D1%8F+%26+email").contains("status=working");
        request.addParameter("status", "closed");
        assertThatThrownBy(() -> SupportRequests.query(request, false)).isInstanceOf(InvalidRequestException.class);
        for (String[] filter : new String[][]{{"status", "draft"}, {"limit", "101"}, {"limit", "0"}, {"userId", "-1"},
                {"accountId", "person@example.test"}, {"q", " "}, {"sort", "desc"}}) {
            var invalid = new MockHttpServletRequest();
            invalid.addParameter(filter[0], filter[1]);
            assertThatThrownBy(() -> SupportRequests.query(invalid, false)).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> SupportRequests.query(request, true)).isInstanceOf(InvalidRequestException.class);
    }

    @Test void numericIdsRemainStringsAndUnsafeVersionsOrUuidCommandsAreRejected() {
        assertThat(SupportRequests.numeric("9223372036854775807")).isEqualTo("9223372036854775807");
        for (String id : new String[]{"9223372036854775808", "0", "01", "-1", "1/commands", "1%2fcommands"}) {
            assertThatThrownBy(() -> SupportRequests.numeric(id)).isInstanceOf(InvalidRequestException.class);
        }
        String valid = new String(body("reply", "\"text\":\"Answer\"").readAllBytes(), StandardCharsets.UTF_8);
        for (String invalid : new String[]{valid.replace("\"expectedVersion\":1", "\"expectedVersion\":true"),
                valid.replace("\"expectedVersion\":1", "\"expectedVersion\":9007199254740992"),
                valid.replace("\"expectedVersion\":1", "\"expectedVersion\":-1")}) {
            assertThatThrownBy(() -> SupportRequests.command(stream(invalid), ACTOR)).isInstanceOf(InvalidRequestException.class);
        }
    }

    private static ByteArrayInputStream body(String kind, String specific) {
        return stream("{\"commandId\":\""+UUID.randomUUID()+"\",\"expectedVersion\":1,\"type\":\""+kind+"\","+specific+"}");
    }

    private static ByteArrayInputStream stream(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}

package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bodies of the access routes are exact: every field, nothing else, no coercion, and malformed JSON is the opaque 400, never a raw parser failure. */
class AccessCommandsTest {
    private static InputStream body(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }

    private static final Map<String, Function<InputStream, ?>> PARSERS = Map.of(
            "invite", AccessCommands::invite, "token", AccessCommands::token, "username", AccessCommands::username);

    @Test
    void malformedJsonIsTheOpaqueInvalidRequestForEveryParser() {
        for (var parser : PARSERS.entrySet()) {
            for (var malformed : MalformedJsonBodies.all()) {
                assertThatThrownBy(() -> parser.getValue().apply(new ByteArrayInputStream(malformed.bytes())))
                        .as("%s with %s", parser.getKey(), malformed)
                        .isExactlyInstanceOf(InvalidRequestException.class).hasMessage("Invalid request").hasNoCause();
            }
        }
    }

    @Test
    void anInviteSpecHasBothFieldsAsIntegersOrNull() {
        assertThat(AccessCommands.invite(body("{\"maxUses\":1,\"expiresInDays\":7}"))).isEqualTo(new AccessCommands.InviteSpec(1, 7));
        assertThat(AccessCommands.invite(body("{\"maxUses\":null,\"expiresInDays\":null}"))).isEqualTo(new AccessCommands.InviteSpec(null, null));
        for (String bad : new String[] {"{}", "{\"maxUses\":1}", "{\"maxUses\":1,\"expiresInDays\":7,\"x\":1}", "{\"maxUses\":\"1\",\"expiresInDays\":7}",
                "{\"maxUses\":1.5,\"expiresInDays\":7}", "{\"maxUses\":1,\"expiresInDays\":true}", "{\"maxUses\":99999999999,\"expiresInDays\":7}",
                "{\"maxUses\":[1],\"expiresInDays\":7}"}) {
            assertThatThrownBy(() -> AccessCommands.invite(body(bad))).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void aTokenAndAUsernameAreTheOnlyFieldOfTheirBody() {
        assertThat(AccessCommands.token(body("{\"token\":\"abc\"}"))).isEqualTo("abc");
        assertThat(AccessCommands.username(body("{\"username\":\"anna.k\"}"))).isEqualTo("anna.k");
        for (String bad : new String[] {"{}", "{\"token\":1}", "{\"token\":null}", "{\"token\":\"a\",\"username\":\"b\"}", "{\"username\":\"a\"}"}) {
            assertThatThrownBy(() -> AccessCommands.token(body(bad))).as(bad).isInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> AccessCommands.token(body("{\"token\":\"" + "a".repeat(1100) + "\"}"))).isInstanceOf(InvalidRequestException.class);
    }
}

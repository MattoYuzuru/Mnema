package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Package-private request parsers keep answering malformed JSON with the opaque invalid-request failure. */
class AuthoringJsonBoundaryTest {

    private static final Map<String, Function<InputStream, ?>> PARSERS = Map.ofEntries(
            Map.entry("AuthoringCommands.draftCreate", AuthoringCommands::draftCreate),
            Map.entry("AuthoringCommands.draftUpdate", AuthoringCommands::draftUpdate),
            Map.entry("AuthoringCommands.captureCreate", AuthoringCommands::captureCreate),
            Map.entry("AuthoringCommands.captureUpdate", AuthoringCommands::captureUpdate),
            Map.entry("AuthoringCommands.captureArchive", AuthoringCommands::captureArchive),
            Map.entry("AuthoringCommands.captureConvert", AuthoringCommands::captureConvert));

    @Test
    void malformedJsonIsAlwaysTheOpaqueInvalidRequestFailure() {
        for (var parser : PARSERS.entrySet()) {
            for (var body : MalformedJsonBodies.all()) {
                assertThatThrownBy(() -> parser.getValue().apply(new ByteArrayInputStream(body.bytes())))
                        .as("%s with %s", parser.getKey(), body)
                        .isExactlyInstanceOf(InvalidRequestException.class)
                        .hasMessage("Invalid request")
                        .hasNoCause();
            }
        }
    }
}

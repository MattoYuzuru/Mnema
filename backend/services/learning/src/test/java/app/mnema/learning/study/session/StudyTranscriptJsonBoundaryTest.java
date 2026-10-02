package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Package-private request parsers keep answering malformed JSON with the opaque invalid-request failure. */
class StudyTranscriptJsonBoundaryTest {

    private static final Map<String, Function<InputStream, ?>> PARSERS = Map.ofEntries(
            Map.entry("StudyTranscriptCommand.nonce", StudyTranscriptCommand::nonce));

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

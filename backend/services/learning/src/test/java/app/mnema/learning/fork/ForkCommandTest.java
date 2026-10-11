package app.mnema.learning.fork;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The strict body of {@code POST /api/decks/forks}: exactly {@code {commandId, sourceCode}}, and every malformed shape is the opaque 400. */
class ForkCommandTest {
    private static final String COMMAND = "018f1d98-5c10-7abc-8abc-0123456789ab";

    private static ForkCommand read(String body) {
        return ForkCommand.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsTheTwoFieldsAndNothingElse() {
        ForkCommand command = read("{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":\"Kq7xT3mNpR\"}");
        assertThat(command.commandId()).isEqualTo(UUID.fromString(COMMAND));
        assertThat(command.sourceCode()).isEqualTo("Kq7xT3mNpR");
        // field order is free; a code of any shape is accepted here (the access decision answers 404 for an unknown one)
        assertThat(read("{\"sourceCode\":\"x\",\"commandId\":\"" + COMMAND + "\"}").sourceCode()).isEqualTo("x");
        assertThat(command.envelope().path("sourceCode").stringValue(null)).isEqualTo("Kq7xT3mNpR");
    }

    @Test
    void everyOtherShapeIsInvalid() {
        for (String body : new String[] {
                "{}", "{\"commandId\":\"" + COMMAND + "\"}", "{\"sourceCode\":\"Kq7xT3mNpR\"}",
                "{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":\"Kq7xT3mNpR\",\"extra\":1}",
                "{\"commandId\":1,\"sourceCode\":\"Kq7xT3mNpR\"}", "{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":7}",
                "{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":null}", "{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":\"\"}",
                "{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":\"" + "x".repeat(65) + "\"}",
                "{\"commandId\":\"not-a-uuid\",\"sourceCode\":\"Kq7xT3mNpR\"}",
                "{\"commandId\":\"00000000-0000-0000-0000-000000000000\",\"sourceCode\":\"Kq7xT3mNpR\"}",
                // a deck id is not a command id (version 4/7 only) and the hex digits are canonical
                "{\"commandId\":\"{" + COMMAND + "}\",\"sourceCode\":\"Kq7xT3mNpR\"}",
                "[\"" + COMMAND + "\"]", "\"" + COMMAND + "\"", "null", "{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":[\"Kq7xT3mNpR\"]}"}) {
            assertThatThrownBy(() -> read(body)).as(body).isExactlyInstanceOf(InvalidRequestException.class);
        }
        assertThatThrownBy(() -> new ForkCommand(null, "x")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new ForkCommand(UUID.fromString(COMMAND), null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aBodyOverTheLimitIsInvalid() {
        String padded = "{\"commandId\":\"" + COMMAND + "\",\"sourceCode\":\"" + "a".repeat(ForkCommand.MAX_REQUEST_BYTES) + "\"}";
        assertThatThrownBy(() -> read(padded)).isExactlyInstanceOf(InvalidRequestException.class);
    }

    @Test
    void malformedJsonIsTheOpaqueInvalidRequestFailure() {
        for (var body : MalformedJsonBodies.all()) {
            assertThatThrownBy(() -> ForkCommand.read(new ByteArrayInputStream(body.bytes()))).as(body.toString())
                    .isExactlyInstanceOf(InvalidRequestException.class).hasMessage("Invalid request").hasNoCause();
        }
    }
}

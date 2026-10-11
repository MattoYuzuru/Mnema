package app.mnema.learning.fork;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The strict body of {@code PUT /api/decks/{deckId}/fork:auto-update}: exactly {@code {autoUpdate: boolean}}, every other shape is the opaque 400. */
class AutoUpdateCommandTest {
    private static AutoUpdateCommand read(String body) {
        return AutoUpdateCommand.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsTheOneBooleanField() {
        assertThat(read("{\"autoUpdate\":true}").autoUpdate()).isTrue();
        assertThat(read("{ \"autoUpdate\" : false }\n").autoUpdate()).isFalse();
    }

    @Test
    void everyOtherShapeIsInvalid() {
        for (String body : new String[] {"{}", "{\"autoUpdate\":\"true\"}", "{\"autoUpdate\":1}", "{\"autoUpdate\":0}", "{\"autoUpdate\":null}", "{\"autoUpdate\":[true]}",
                "{\"autoUpdate\":true,\"extra\":1}", "{\"autoUpdate\":true,\"autoUpdate\":true}", "{\"AutoUpdate\":true}", "[true]", "true", "null", "", "{\"autoUpdate\":true"}) {
            assertThatThrownBy(() -> read(body)).as(body).isExactlyInstanceOf(InvalidRequestException.class);
        }
        String padded = "{\"autoUpdate\":true,\"x\":\"" + "a".repeat(AutoUpdateCommand.MAX_REQUEST_BYTES) + "\"}";
        assertThatThrownBy(() -> read(padded)).isExactlyInstanceOf(InvalidRequestException.class);
    }

    @Test
    void malformedJsonIsTheOpaqueInvalidRequestFailure() {
        for (var body : MalformedJsonBodies.all()) {
            assertThatThrownBy(() -> AutoUpdateCommand.read(new ByteArrayInputStream(body.bytes()))).as(body.toString())
                    .isExactlyInstanceOf(InvalidRequestException.class).hasMessage("Invalid request").hasNoCause();
        }
    }
}

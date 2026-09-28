package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;

/** Strict transcript reveal command; the nonce binds it to one issued presentation. */
final class StudyTranscriptCommand {
    private static final int MAX_BYTES = 256;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 3, 16);

    private StudyTranscriptCommand() { }

    static String nonce(InputStream input) {
        try {
            JsonNode value = JSON.read(input.readNBytes(MAX_BYTES + 1));
            if (!value.isObject() || value.size() != 1 || !value.has("nonce")
                    || !value.path("nonce").isTextual() || value.path("nonce").textValue().length() < 16
                    || value.path("nonce").textValue().length() > 100) throw new InvalidRequestException();
            return value.path("nonce").textValue();
        } catch (IOException | IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }
}

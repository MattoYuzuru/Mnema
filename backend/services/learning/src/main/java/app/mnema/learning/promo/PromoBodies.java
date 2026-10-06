package app.mnema.learning.promo;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** Strict request reading shared by the promo, experiment and popup controllers: bounded JSON, exactly the named members, nothing else. */
public final class PromoBodies {
    private static final int MAX_BODY_BYTES = 2_048;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_BODY_BYTES, 3, 64);

    private PromoBodies() { }

    /** Reads an object whose members are all in {@code allowed} and which has every member of {@code required}. */
    public static JsonNode object(InputStream input, Set<String> required, Set<String> allowed) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_BODY_BYTES + 1));
            if (!body.isObject()) throw new InvalidRequestException();
            for (String name : required) if (!body.has(name)) throw new InvalidRequestException();
            for (String name : body.propertyNames()) if (!allowed.contains(name)) throw new InvalidRequestException();
            return body;
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** @return the string member, or null when absent or null; a member of another type is an invalid request */
    public static String text(JsonNode body, String name) {
        JsonNode member = body.path(name);
        if (member.isMissingNode() || member.isNull()) return null;
        if (!member.isString()) throw new InvalidRequestException();
        return member.stringValue(null);
    }

    /** @return the integer member, or null when absent or null */
    public static Integer integer(JsonNode body, String name) {
        JsonNode member = body.path(name);
        if (member.isMissingNode() || member.isNull()) return null;
        if (!member.isIntegralNumber() || !member.canConvertToInt()) throw new InvalidRequestException();
        return member.intValue();
    }

    /** @return the boolean member, or null when absent or null */
    public static Boolean flag(JsonNode body, String name) {
        JsonNode member = body.path(name);
        if (member.isMissingNode() || member.isNull()) return null;
        if (!member.isBoolean()) throw new InvalidRequestException();
        return member.booleanValue();
    }

    /** The token subject as an entity id; a malformed subject is an invalid request. */
    public static UUID owner(Jwt identity) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(identity.getSubject()), "owner");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}

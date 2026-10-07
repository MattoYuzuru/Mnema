package app.mnema.learning.events;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;

/** Bounded JSON, dates, keyset locations and optimistic preconditions at the HTTP boundary. */
final class EventRequests {
    static final int PAGE_SIZE = 50;
    static final int MAX_REQUEST_BYTES = 65_536;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_REQUEST_BYTES, 2, 32);
    private static final Set<String> FIELDS = Set.of("commandId", "title", "bodyMarkdown", "eventDate", "published");

    private EventRequests() { }

    record Command(UUID commandId, String title, String bodyMarkdown, LocalDate eventDate, boolean published) {
        ObjectNode envelope(UUID eventId, Long version) {
            ObjectNode value = JsonNodeFactory.instance.objectNode().put("title", title).put("bodyMarkdown", bodyMarkdown)
                    .put("eventDate", eventDate.toString()).put("published", published);
            if (eventId != null) value.put("eventId", eventId.toString());
            if (version != null) value.put("expectedVersion", version.toString());
            return value;
        }
    }

    record Cursor(LocalDate date, UUID eventId) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((date + "/" + eventId).getBytes(StandardCharsets.US_ASCII));
        }
    }

    static Command command(InputStream stream) {
        try {
            JsonNode body = JSON.read(stream.readNBytes(MAX_REQUEST_BYTES + 1));
            if (body.size() != FIELDS.size() || body.properties().stream().anyMatch(field -> !FIELDS.contains(field.getKey()))
                    || !body.path("published").isBoolean()) throw new InvalidRequestException();
            String title = text(body, "title");
            String markdown = text(body, "bodyMarkdown");
            if (title.isBlank() || !title.equals(title.strip()) || title.codePointCount(0, title.length()) > 160
                    || title.codePoints().anyMatch(Character::isISOControl) || markdown.isBlank()
                    || markdown.codePointCount(0, markdown.length()) > 16_000
                    || markdown.codePoints().anyMatch(point -> Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t')) {
                throw new InvalidRequestException();
            }
            return new Command(commandId(text(body, "commandId")), title, markdown, date(text(body, "eventDate")),
                    body.path("published").booleanValue());
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    static UUID entityId(String value) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "eventId");
            if (!id.toString().equals(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    static UUID commandId(String value) {
        try {
            return UuidPolicy.requireCommandId(entityId(value));
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    static Cursor cursor(String value) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 80 || !value.matches("[A-Za-z0-9_-]+")) throw new InvalidRequestException();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            String[] parts = decoded.split("/", -1);
            if (parts.length != 2) throw new InvalidRequestException();
            Cursor cursor = new Cursor(date(parts[0]), entityId(parts[1]));
            if (!cursor.encode().equals(value)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    static String parameter(HttpServletRequest request, String name) {
        if (request.getParameterMap().keySet().stream().anyMatch(key -> !key.equals(name))) throw new InvalidRequestException();
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }

    static long version(Enumeration<String> headers) {
        if (headers == null || !headers.hasMoreElements()) throw new VersionPreconditionRequiredException();
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.length() > 21 || !value.matches("\"(0|[1-9][0-9]{0,18})\"")) {
            throw new InvalidRequestException();
        }
        try {
            long parsed = Long.parseLong(value.substring(1, value.length() - 1));
            if (parsed == Long.MAX_VALUE) throw new InvalidRequestException();
            return parsed;
        } catch (NumberFormatException failure) {
            throw new InvalidRequestException();
        }
    }

    private static String text(JsonNode body, String name) {
        if (!body.path(name).isString()) throw new InvalidRequestException();
        return body.path(name).stringValue(null);
    }

    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new InvalidRequestException();
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw new InvalidRequestException();
            return date;
        } catch (DateTimeParseException failure) {
            throw new InvalidRequestException();
        }
    }
}

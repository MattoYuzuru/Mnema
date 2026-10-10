package app.mnema.learning.admin.support;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

/** Exact, bounded request shapes; the authenticated actor is added after parsing. */
final class SupportRequests {
    static final Set<String> STATUSES = Set.of("open", "working", "waiting", "closed");
    static final Set<String> CATEGORIES = Set.of("bug", "idea", "question", "other");
    static final Set<String> DELIVERIES = Set.of("queued", "sending", "sent", "failed", "uncertain");
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int MAX_BYTES = 16_384;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 2, 32);
    private static final Set<String> LIST = Set.of("status", "category", "delivery", "userId", "accountId", "q", "before", "limit");
    private static final Set<String> CONVERSATION = Set.of("afterMessage", "limit");

    private SupportRequests() { }

    static String query(HttpServletRequest request, boolean conversation) {
        Set<String> allowed = conversation ? CONVERSATION : LIST;
        var result = new ArrayList<String>();
        for (var entry : request.getParameterMap().entrySet()) {
            String key = entry.getKey();
            String[] values = entry.getValue();
            if (!allowed.contains(key) || values.length != 1) throw new InvalidRequestException();
            String value = values[0];
            switch (key) {
                case "status" -> choice(value, STATUSES);
                case "category" -> choice(value, CATEGORIES);
                case "delivery" -> choice(value, DELIVERIES);
                case "accountId" -> uuid(value, false);
                case "q" -> {
                    if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 100
                            || value.codePoints().anyMatch(Character::isISOControl)) throw new InvalidRequestException();
                }
                case "limit" -> { if (Long.parseLong(numeric(value)) > 100) throw new InvalidRequestException(); }
                default -> numeric(value);
            }
            result.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return result.isEmpty() ? "" : "?" + String.join("&", result);
    }

    static ObjectNode command(InputStream stream, UUID actor) {
        try {
            JsonNode body = JSON.read(stream.readNBytes(MAX_BYTES + 1));
            String type = text(body, "type");
            choice(type, Set.of("reply", "note", "status"));
            Set<String> fields = Set.of("commandId", "expectedVersion", "type", type.equals("status") ? "status" : "text");
            if (body.size() != fields.size() || body.properties().stream().anyMatch(field -> !fields.contains(field.getKey()))) {
                throw new InvalidRequestException();
            }
            uuid(text(body, "commandId"), true);
            JsonNode version = body.path("expectedVersion");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0
                    || version.longValue() >= MAX_SAFE_INTEGER) throw new InvalidRequestException();
            if (type.equals("status")) choice(text(body, "status"), STATUSES);
            else {
                String value = text(body, "text");
                if (value.isBlank() || value.codePointCount(0, value.length()) > 3500
                        || value.codePoints().anyMatch(point -> Character.isISOControl(point) && point != '\n'
                        && point != '\r' && point != '\t')) throw new InvalidRequestException();
            }
            ObjectNode result = (ObjectNode) body;
            result.put("actorAccountId", actor.toString());
            return result;
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    static String numeric(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}") || Long.parseLong(value) < 1) throw new InvalidRequestException();
            return value;
        } catch (NumberFormatException failure) { throw new InvalidRequestException(); }
    }

    static UUID uuid(String value, boolean command) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID parsed = UuidPolicy.requireEntityId(UUID.fromString(value), "support identity");
            if (!parsed.toString().equals(value)) throw new InvalidRequestException();
            return command ? UuidPolicy.requireCommandId(parsed) : parsed;
        } catch (IllegalArgumentException failure) { throw new InvalidRequestException(); }
    }

    private static String text(JsonNode body, String key) {
        if (!body.path(key).isString()) throw new InvalidRequestException();
        return body.path(key).stringValue(null);
    }

    private static void choice(String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) throw new InvalidRequestException();
    }
}

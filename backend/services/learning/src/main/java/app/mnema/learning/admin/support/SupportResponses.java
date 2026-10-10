package app.mnema.learning.admin.support;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

/** Never forward provider payloads, file references or unexpected private fields to a browser. */
final class SupportResponses {
    private static final Set<String> TICKET = Set.of("id", "version", "category", "status", "userId", "username", "firstName",
            "accountId", "createdAt", "submittedAt", "updatedAt", "latestDelivery");
    private static final Set<String> MESSAGE = Set.of("id", "direction", "text", "createdAt", "attachment", "delivery");
    private static final Set<String> ACK = Set.of("commandId", "ticketId", "version", "messageId", "outboxId", "delivery");

    private SupportResponses() { }

    static void validate(JsonNode root, boolean command, boolean conversation) {
        if (command) {
            fields(root, ACK);
            SupportRequests.uuid(text(root.path("commandId"), 36), true);
            id(root.path("ticketId"), false);
            version(root.path("version"));
            id(root.path("messageId"), true);
            id(root.path("outboxId"), true);
            delivery(root.path("delivery"));
        } else if (conversation) {
            fields(root, Set.of("ticket", "messages", "nextMessageCursor"));
            ticket(root.path("ticket"));
            JsonNode messages = root.path("messages");
            require(messages.isArray() && messages.size() <= 100);
            for (JsonNode message : messages) {
                fields(message, MESSAGE);
                id(message.path("id"), false);
                require(Set.of("in", "out", "note").contains(text(message.path("direction"), 4)));
                text(message.path("text"), 4000);
                date(message.path("createdAt"));
                delivery(message.path("delivery"));
                JsonNode attachment = message.path("attachment");
                if (!attachment.isNull()) {
                    fields(attachment, Set.of("kind", "name", "size", "mimeType"));
                    require(Set.of("photo", "document", "video", "audio", "voice", "animation", "video_note")
                            .contains(text(attachment.path("kind"), 10)));
                    text(attachment.path("name"), 255);
                    text(attachment.path("mimeType"), 100);
                    JsonNode size = attachment.path("size");
                    // Telegram file_size is optional; an unknown size is not a zero-byte file.
                    require(size.isNull() || size.isIntegralNumber() && size.canConvertToLong()
                            && size.longValue() >= 0 && size.longValue() <= 20 * 1024 * 1024);
                }
            }
            id(root.path("nextMessageCursor"), true);
        } else {
            fields(root, Set.of("entries", "nextCursor"));
            require(root.path("entries").isArray() && root.path("entries").size() <= 100);
            root.path("entries").forEach(SupportResponses::ticket);
            id(root.path("nextCursor"), true);
        }
    }

    private static void ticket(JsonNode ticket) {
        fields(ticket, TICKET);
        id(ticket.path("id"), false);
        id(ticket.path("userId"), false);
        version(ticket.path("version"));
        require(SupportRequests.STATUSES.contains(text(ticket.path("status"), 7)));
        require(SupportRequests.CATEGORIES.contains(text(ticket.path("category"), 8)));
        if (!ticket.path("username").isNull()) text(ticket.path("username"), 100);
        text(ticket.path("firstName"), 100);
        if (!ticket.path("accountId").isNull()) SupportRequests.uuid(text(ticket.path("accountId"), 36), false);
        date(ticket.path("createdAt"));
        if (!ticket.path("submittedAt").isNull()) date(ticket.path("submittedAt"));
        date(ticket.path("updatedAt"));
        delivery(ticket.path("latestDelivery"));
    }

    private static void fields(JsonNode node, Set<String> names) {
        require(node.isObject() && node.size() == names.size()
                && node.properties().stream().allMatch(field -> names.contains(field.getKey())));
    }

    private static String text(JsonNode node, int max) {
        require(node.isString());
        String value = node.stringValue();
        require(value.codePointCount(0, value.length()) <= max);
        return value;
    }

    private static void id(JsonNode node, boolean nullable) {
        if (!(nullable && node.isNull())) SupportRequests.numeric(text(node, 19));
    }

    private static void version(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0
                && node.longValue() < SupportRequests.MAX_SAFE_INTEGER);
    }

    private static void date(JsonNode node) { Instant.parse(text(node, 40)); }

    private static void delivery(JsonNode node) {
        if (!node.isNull()) require(SupportRequests.DELIVERIES.contains(text(node, 9)));
    }

    private static void require(boolean condition) {
        if (!condition) throw new SupportUnavailableException();
    }
}

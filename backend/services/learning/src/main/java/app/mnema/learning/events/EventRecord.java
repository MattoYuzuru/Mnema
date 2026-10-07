package app.mnema.learning.events;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record EventRecord(UUID eventId, String title, String bodyMarkdown, LocalDate eventDate, boolean published,
                   Instant publishedAt, long rowVersion, Instant createdAt, Instant updatedAt) {
    ObjectNode publicView() {
        ObjectNode view = JsonNodeFactory.instance.objectNode().put("eventId", eventId.toString()).put("title", title)
                .put("bodyMarkdown", bodyMarkdown).put("eventDate", eventDate.toString());
        if (publishedAt == null) view.putNull("publishedAt");
        else view.put("publishedAt", publishedAt.toString());
        return view;
    }

    ObjectNode adminView() {
        return publicView().put("published", published).put("rowVersion", Long.toString(rowVersion))
                .put("createdAt", createdAt.toString()).put("updatedAt", updatedAt.toString());
    }
}

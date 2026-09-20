package app.mnema.learning.study.restart;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Explicit bounded material restart command. */
public record StudyRestartCommand(UUID commandId, List<UUID> memberKeys, ObjectNode payload) {
    private static final int MAX_BYTES = 8_192;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 5, 150);

    public StudyRestartCommand {
        commandId = UuidPolicy.requireCommandId(commandId);
        memberKeys = List.copyOf(memberKeys);
        payload = payload.deepCopy();
    }

    @Override public ObjectNode payload() { return payload.deepCopy(); }

    public ObjectNode envelope(UUID deck) {
        ObjectNode value = payload();
        value.put("deckId", deck.toString());
        return value;
    }

    public static StudyRestartCommand read(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            fields(body, Set.of("commandId", "memberKeys"));
            if (!body.path("memberKeys").isArray() || body.path("memberKeys").isEmpty()
                    || body.path("memberKeys").size() > 100) throw invalid();
            List<UUID> members = new ArrayList<>();
            Set<UUID> distinct = new HashSet<>();
            body.path("memberKeys").forEach(value -> {
                UUID member = id(value, false);
                if (!distinct.add(member)) throw invalid();
                members.add(member);
            });
            return new StudyRestartCommand(id(body.path("commandId"), true), members, (ObjectNode) body);
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }

    private static UUID id(JsonNode value, boolean command) {
        if (!value.isTextual() || value.textValue().length() != 36) throw invalid();
        try {
            UUID id = UUID.fromString(value.textValue());
            id = command ? UuidPolicy.requireCommandId(id) : UuidPolicy.requireEntityId(id, "id");
            if (!id.toString().equals(value.textValue())) throw invalid();
            return id;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static void fields(JsonNode value, Set<String> expected) {
        if (!value.isObject() || !value.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(expected)) throw invalid();
    }

    private static InvalidRequestException invalid() { return new InvalidRequestException(); }
}

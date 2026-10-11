package app.mnema.learning.update.apply;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import app.mnema.learning.update.plan.PlanVersion;
import app.mnema.learning.update.plan.UnitKind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Strict, bounded body of {@code POST /api/decks/{forkDeckId}/update-plan:apply} (Updates/4, #443, contract {@code contracts/decks/updates.json}):
 * {@code {commandId, planVersion, acceptSafe?, choices?}} and nothing else, at most {@value #MAX_REQUEST_BYTES} bytes. The client never sends a revision id or an ordinal: it names
 * units of the plan it read and what to do with them, and the server builds every operation from the plan it recomputes. A unit is named at most once.
 *
 * @param commandId the idempotency key (UUIDv4 or v7)
 * @param planVersion the version of the plan the owner read (a different current one is {@code 409 UPDATE_PLAN_STALE})
 * @param acceptSafe take every NEW and SAFE unit the choices do not name (never a conflict, a unit the author removed, or a question asked again)
 * @param choices what to do with the units the owner decided one by one
 */
public record ApplyCommand(UUID commandId, String planVersion, boolean acceptSafe, List<Pick> choices) {
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_REQUEST_BYTES, 4, 30_000);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One decided unit. */
    public record Pick(UnitKind kind, UUID key, ApplyChoice choice) {
        public String unitId() { return kind.unitId(key); }
    }

    public ApplyCommand {
        try {
            UuidPolicy.requireCommandId(commandId);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidRequestException();
        }
        if (!PlanVersion.valid(planVersion)) throw new InvalidRequestException();
        choices = List.copyOf(choices);
        Set<String> seen = new HashSet<>();
        for (Pick pick : choices) {
            if (!seen.add(pick.unitId())) throw InvalidRequestException.because("duplicate_unit");
        }
    }

    /** @throws InvalidRequestException for anything but the exact shape, and for a body above {@value #MAX_REQUEST_BYTES} bytes */
    public static ApplyCommand read(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            if (!body.isObject() || !body.has("commandId") || !body.has("planVersion")) throw new InvalidRequestException();
            for (var field : body.properties()) {
                if (!Set.of("commandId", "planVersion", "acceptSafe", "choices").contains(field.getKey())) throw new InvalidRequestException();
            }
            UUID command = uuid(text(body, "commandId"));
            boolean acceptSafe = false;
            if (body.has("acceptSafe")) {
                if (!body.path("acceptSafe").isBoolean()) throw new InvalidRequestException();
                acceptSafe = body.path("acceptSafe").booleanValue(false);
            }
            List<Pick> picks = new ArrayList<>();
            if (body.has("choices")) {
                if (!body.path("choices").isArray()) throw new InvalidRequestException();
                for (JsonNode entry : body.path("choices")) picks.add(pick(entry));
            }
            return new ApplyCommand(command, text(body, "planVersion"), acceptSafe, picks);
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw new InvalidRequestException();
        }
    }

    private static Pick pick(JsonNode entry) {
        if (!entry.isObject() || entry.size() != 2 || !entry.has("unitId") || !entry.has("choice")) throw new InvalidRequestException();
        String unit = text(entry, "unitId");
        int colon = unit.indexOf(':');
        if (colon < 1) throw new InvalidRequestException();
        UnitKind kind = UnitKind.fromWire(unit.substring(0, colon)).orElseThrow(InvalidRequestException::new);
        UUID key = uuid(unit.substring(colon + 1));
        ApplyChoice choice = ApplyChoice.fromWire(text(entry, "choice")).orElseThrow(InvalidRequestException::new);
        return new Pick(kind, key, choice);
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isString()) throw new InvalidRequestException();
        return node.path(field).stringValue(null);
    }

    /** A canonical, lowercase, 36-character identifier. */
    private static UUID uuid(String value) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "id");
            if (!id.toString().equals(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidRequestException();
        }
    }

    /**
     * The identity of the command for the receipt (the id and the owner are bound by the receipt itself): the copy, the plan, and what to do, with the choices in the order of their unit ids
     * so that the order the client listed them in is not part of the command.
     */
    public ObjectNode envelope(UUID deckId) {
        ObjectNode envelope = JSON.createObjectNode().put("deckId", deckId.toString()).put("planVersion", planVersion).put("acceptSafe", acceptSafe);
        ArrayNode sorted = envelope.putArray("choices");
        choices.stream().sorted(Comparator.comparing(Pick::unitId)).forEach(pick -> sorted.addObject().put("unitId", pick.unitId()).put("choice", pick.choice().name()));
        return envelope;
    }
}

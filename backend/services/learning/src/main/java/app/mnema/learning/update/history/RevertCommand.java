package app.mnema.learning.update.history;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
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
 * Strict, bounded body of {@code POST /api/decks/{deckId}/update-history/{deckRevisionId}/units/{kind}/{key}:revert} (Updates/6 part B, #445):
 * {@code {commandId, with?: ["KIND:key", ...]}} and nothing else, at most {@value #MAX_REQUEST_BYTES} bytes. {@code with} names the OTHER units of the same apply that are returned
 * in the same command (at most {@value #MAX_WITH}); a material and the exercises that were taken with it are returned only together, and the refusal that says so lists them.
 *
 * @param commandId the idempotency key (UUIDv4 or v7)
 * @param with the units returned together with the unit of the path, in the order given; none repeated, none the unit of the path
 */
record RevertCommand(UUID commandId, List<Unit> with) {
    static final int MAX_REQUEST_BYTES = 4 * 1024;
    static final int MAX_WITH = 20;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_REQUEST_BYTES, 4, 1_000);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A unit of the apply: a material or an exercise. */
    record Unit(UnitKind kind, UUID key) {
        Unit {
            if (!HistoryUnit.returnable(kind)) throw InvalidRequestException.because("unit_not_returnable");
        }

        String unitId() { return kind.unitId(key); }
    }

    RevertCommand {
        try {
            UuidPolicy.requireCommandId(commandId);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidRequestException();
        }
        with = List.copyOf(with);
        if (with.size() > MAX_WITH) throw new InvalidRequestException();
        Set<String> seen = new HashSet<>();
        for (Unit unit : with) {
            if (!seen.add(unit.unitId())) throw InvalidRequestException.because("duplicate_unit");
        }
    }

    /** @throws InvalidRequestException for anything but the exact shape, and for a body above {@value #MAX_REQUEST_BYTES} bytes */
    static RevertCommand read(InputStream input) {
        try {
            JsonNode body = READER.read(input.readNBytes(MAX_REQUEST_BYTES + 1));
            if (!body.isObject() || !body.has("commandId")) throw new InvalidRequestException();
            for (var field : body.properties()) {
                if (!Set.of("commandId", "with").contains(field.getKey())) throw new InvalidRequestException();
            }
            if (!body.path("commandId").isString()) throw new InvalidRequestException();
            UUID command = uuid(body.path("commandId").stringValue(null));
            List<Unit> with = new ArrayList<>();
            if (body.has("with")) {
                if (!body.path("with").isArray()) throw new InvalidRequestException();
                for (JsonNode entry : body.path("with")) {
                    if (!entry.isString()) throw new InvalidRequestException();
                    with.add(unit(entry.stringValue(null)));
                }
            }
            return new RevertCommand(command, with);
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw new InvalidRequestException();
        }
    }

    static Unit unit(String unitId) {
        int colon = unitId.indexOf(':');
        if (colon < 1) throw new InvalidRequestException();
        UnitKind kind = UnitKind.fromWire(unitId.substring(0, colon)).orElseThrow(InvalidRequestException::new);
        return new Unit(kind, uuid(unitId.substring(colon + 1)));
    }

    /** A canonical, lowercase, 36-character identifier. */
    static UUID uuid(String value) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "id");
            if (!id.toString().equals(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidRequestException();
        }
    }

    /** The identity of the command for the receipt (the id and the owner are bound by the receipt itself): the apply, the unit of the path and the others, sorted. */
    ObjectNode envelope(UUID deckId, UUID deckRevisionId, Unit primary) {
        ObjectNode envelope = JSON.createObjectNode().put("deckId", deckId.toString()).put("deckRevisionId", deckRevisionId.toString()).put("unitId", primary.unitId());
        ArrayNode sorted = envelope.putArray("with");
        with.stream().map(Unit::unitId).sorted(Comparator.naturalOrder()).forEach(sorted::add);
        return envelope;
    }
}

package app.mnema.learning.update.history;

import app.mnema.learning.update.FinalWrites;
import app.mnema.learning.update.plan.UnitKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@link FinalWrites} of a return command ({@value #TYPE}): what {@link RevertFinalWriter} writes in the final transaction of the combined publication. The decision rows keep
 * the returned units from coming back by themselves: an addition the owner removed is {@code DECLINE_NEW}, a change he undid or a removal he undid is {@code KEEP_MINE}, each
 * remembering the author's state (a revision, or "removed") it covers, so the plan hides the unit until the author changes it again.
 *
 * @param command the client's command id
 * @param request the canonical request (the receipt of the answer is stored with it)
 * @param units the unit ids returned, in the order of the command
 */
record RevertWrites(UUID command, ObjectNode request, List<String> units, List<Row> rows) {
    static final String TYPE = "update.revert";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code resolved} is the author's revision, or {@code "-"} when the author had removed the unit. */
    record Row(UnitKind kind, UUID key, String decision, String resolved) { }

    FinalWrites toFinalWrites() {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString());
        body.set("request", request);
        ArrayNode list = body.putArray("units");
        units.forEach(list::add);
        ArrayNode decided = body.putArray("rows");
        for (Row row : rows) decided.addArray().add(row.kind().name()).add(row.key().toString()).add(row.decision()).add(row.resolved());
        return new FinalWrites(TYPE, body);
    }

    static RevertWrites of(FinalWrites writes) {
        if (!TYPE.equals(writes.type())) throw new IllegalStateException("Not the final writes of a return command");
        ObjectNode body = writes.body();
        List<String> units = new ArrayList<>();
        for (JsonNode unit : body.path("units")) units.add(unit.stringValue(null));
        List<Row> rows = new ArrayList<>();
        for (JsonNode row : body.path("rows")) {
            rows.add(new Row(UnitKind.valueOf(row.get(0).stringValue(null)), UUID.fromString(row.get(1).stringValue(null)), row.get(2).stringValue(null), row.get(3).stringValue(null)));
        }
        if (!(body.path("request") instanceof ObjectNode request)) throw new IllegalStateException("The return command has no request");
        return new RevertWrites(UUID.fromString(body.path("commandId").stringValue(null)), request, units, rows);
    }
}

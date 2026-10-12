package app.mnema.learning.update.apply;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.update.FinalWrites;
import app.mnema.learning.update.apply.ApplyRows.Row;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@link FinalWrites} of an apply command ({@value #TYPE}): what {@link ApplyFinalWriter} must do in the final transaction of the combined publication. The pins it was
 * built against ({@code planVersion}, the base, the published revision) are checked again there; the rows are those of {@link ApplyRows}; the request is the canonical envelope
 * the receipt of the command is stored with, and the counts are the answer.
 *
 * <p>Size. A command lists a row for every unit of the plan that is not taken (the pending ones), so a plan of thousands of units is hundreds of kilobytes
 * ({@code FinalWrites.MAX_BYTES} is 4 MiB since V63). More rows than {@value #MAX_ROWS} is {@code 422 RESOURCE_LIMIT_EXCEEDED}.
 */
record ApplyWrites(UUID command, String planVersion, UUID base, UUID published, ObjectNode request, int applied, int declined, int pending, int alsoAccepted, List<Row> rows,
                   String mode, List<ObjectiveAdoption> objectives) {
    static final String TYPE = "update.apply";
    /** The owner's command. */
    static final String MANUAL = "MANUAL";
    /** The background job of {@code learning.updates.auto}. */
    static final String AUTO = "AUTO";

    /** An objective head the apply moves: logged for «Что нового», because objectives have no journal of their own. {@code previous} is null when the copy had no head. */
    record ObjectiveAdoption(UUID objectiveId, UUID previous, UUID adopted) { }

    ApplyWrites {
        rows = List.copyOf(rows);
        objectives = List.copyOf(objectives);
        if (!MANUAL.equals(mode) && !AUTO.equals(mode)) throw new IllegalArgumentException("mode");
    }

    /** The most rows one command writes: about 150 bytes each against the 4 MiB of the final writes. */
    static final int MAX_ROWS = 20_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    FinalWrites toFinalWrites() {
        if (rows.size() > MAX_ROWS) throw new ResourceLimitExceededException(ProblemExtension.limit(MAX_ROWS));
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString()).put("planVersion", planVersion).put("baseRevisionId", base.toString())
                .put("publishedRevisionId", published.toString()).put("applied", applied).put("declined", declined).put("pending", pending).put("alsoAccepted", alsoAccepted);
        body.set("request", request);
        body.put("mode", mode);
        ArrayNode list = body.putArray("rows");
        rows.forEach(row -> list.add(row.toJson()));
        ArrayNode adopted = body.putArray("objectives");
        for (ObjectiveAdoption objective : objectives) {
            ArrayNode entry = adopted.addArray().add(objective.objectiveId().toString());
            if (objective.previous() == null) entry.addNull(); else entry.add(objective.previous().toString());
            entry.add(objective.adopted().toString());
        }
        return new FinalWrites(TYPE, body);
    }

    static ApplyWrites of(FinalWrites writes) {
        if (!TYPE.equals(writes.type())) throw new IllegalStateException("Not the final writes of an apply command");
        ObjectNode body = writes.body();
        List<Row> rows = new ArrayList<>();
        for (JsonNode row : body.path("rows")) rows.add(Row.fromJson(row));
        List<ObjectiveAdoption> objectives = new ArrayList<>();
        for (JsonNode entry : body.path("objectives")) {
            objectives.add(new ObjectiveAdoption(UUID.fromString(entry.get(0).stringValue(null)), entry.get(1).isNull() ? null : UUID.fromString(entry.get(1).stringValue(null)),
                    UUID.fromString(entry.get(2).stringValue(null))));
        }
        // a command stored before this shape has no mode: it is the owner's
        String mode = body.path("mode").stringValue(MANUAL);
        if (!(body.path("request") instanceof ObjectNode request)) throw new InvalidRequestException();
        return new ApplyWrites(UUID.fromString(body.path("commandId").stringValue(null)), body.path("planVersion").stringValue(null),
                UUID.fromString(body.path("baseRevisionId").stringValue(null)), UUID.fromString(body.path("publishedRevisionId").stringValue(null)), request,
                body.path("applied").intValue(0), body.path("declined").intValue(0), body.path("pending").intValue(0), body.path("alsoAccepted").intValue(0), rows,
                mode, objectives);
    }
}

package app.mnema.learning.platform.json;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.study.attempt.AttemptCommand;
import app.mnema.learning.study.attempt.PairCheckCommand;
import app.mnema.learning.study.restart.StudyRestartCommand;
import app.mnema.learning.study.session.StudyHintCommand;
import app.mnema.learning.study.session.StudySessionCommand;
import app.mnema.learning.support.MalformedJsonBodies;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes the JSON input boundary of every public request parser. Malformed syntax,
 * duplicate keys, trailing tokens and exceeded stream constraints must stay a deterministic
 * invalid-request failure (HTTP 400), independent of the JSON library underneath.
 */
class JsonRequestBoundaryTest {

    private static final Map<String, Function<InputStream, ?>> PARSERS = Map.ofEntries(
            Map.entry("StudyRestartCommand", StudyRestartCommand::read),
            Map.entry("AttemptCommand", AttemptCommand::read),
            Map.entry("PairCheckCommand", PairCheckCommand::read),
            Map.entry("StudySessionCommand", StudySessionCommand::read),
            Map.entry("StudyHintCommand", StudyHintCommand::read),
            Map.entry("ExerciseCommand.create", ExerciseCommand::readCreate),
            Map.entry("ExerciseCommand.update", ExerciseCommand::readUpdate),
            Map.entry("DeckCommand", DeckCommand::read),
            Map.entry("ItemPublicationCommand.create", ItemPublicationCommand::readCreate),
            Map.entry("ItemPublicationCommand.bulk", ItemPublicationCommand::readBulk),
            Map.entry("ItemPublicationCommand.save",
                    input -> ItemPublicationCommand.readSave(input, UUID.randomUUID())));

    @Test
    void everyPublicParserAnswersMalformedJsonWithTheOpaqueInvalidRequestFailure() {
        for (var parser : PARSERS.entrySet()) {
            for (var body : MalformedJsonBodies.all()) {
                assertThatThrownBy(() -> parser.getValue().apply(new ByteArrayInputStream(body.bytes())))
                        .as("%s with %s", parser.getKey(), body)
                        .isExactlyInstanceOf(InvalidRequestException.class)
                        .hasMessage("Invalid request")
                        .hasNoCause();
            }
        }
    }
}

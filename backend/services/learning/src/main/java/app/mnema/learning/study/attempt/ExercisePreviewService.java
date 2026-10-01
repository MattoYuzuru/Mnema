package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.study.session.LearnerContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stateless author-preview evaluation. It has no repository, clock or random source: the exercise arrives in the
 * request and is evaluated by exactly the code Study uses ({@link AttemptEvaluation}, {@link LearnerContent}).
 *
 * <p>Authored content is passed to the evaluator as the learner content: blank, option and side identifiers are the
 * same in both, MATERIAL blocks are never resolved and MATCH sides, ORDER items and CATEGORIZE items are not
 * shuffled because the evaluator only reads identifier sets (and, for ORDER, the learner-visible block equivalence). The editor shows the author's own draft, so there is no reference content to reveal.
 */
@Service
public class ExercisePreviewService {
    private static final ObjectNode NO_REFERENCE_CONTENT =
            JsonNodeFactory.instance.objectNode().set("reference", JsonNodeFactory.instance.arrayNode());

    /** The response body of one action: {@code {feedback}}, {@code {correct}} or {@code {blankId, firstLetter}}. */
    ObjectNode evaluate(ExercisePreviewCommand command) {
        return switch (command.action()) {
            case ExercisePreviewCommand.Submit submit -> submit(command, submit);
            case ExercisePreviewCommand.PairCheck check -> pairCheck(command, check);
            case ExercisePreviewCommand.Hint hint -> hint(command, hint);
        };
    }

    private static ObjectNode submit(ExercisePreviewCommand command, ExercisePreviewCommand.Submit submit) {
        ExerciseType type = command.definition().type();
        // A hint is a first-letter disclosure of a blank whose author enabled it, exactly as in Study.
        if (!firstLetters(command).keySet().containsAll(submit.hintedBlankIds())) throw new InvalidRequestException();
        AttemptEvaluation.Subject subject = new AttemptEvaluation.Subject(type, command.evaluatorPolicy(),
                command.answerKey(), command.content(), NO_REFERENCE_CONTENT, new HashSet<>(submit.hintedBlankIds()),
                submit.transcriptRevealed());
        AttemptEvaluation evaluation = AttemptEvaluation.evaluate(subject, submit.response());
        if (type == ExerciseType.MATCH && evaluation.result() == AttemptEvaluation.Result.CORRECT
                && submit.pairMistakes()) {
            evaluation = evaluation.withPairRetry();
        }
        return JsonNodeFactory.instance.objectNode().set("feedback", evaluation.feedback().deepCopy());
    }

    private static ObjectNode pairCheck(ExercisePreviewCommand command, ExercisePreviewCommand.PairCheck check) {
        if (!(command.definition().key() instanceof AnswerKey.Match key)) throw new InvalidRequestException();
        // Both ids must be sides of this draft; the answer key decides correctness.
        Set<UUID> lefts = new HashSet<>();
        Set<UUID> rights = new HashSet<>();
        sideIds(command.content().path("left"), lefts);
        sideIds(command.content().path("right"), rights);
        if (!lefts.contains(check.leftId()) || !rights.contains(check.rightId())) throw new InvalidRequestException();
        UUID expected = key.pairs().stream().filter(pair -> pair.leftId().equals(check.leftId()))
                .map(AnswerKey.Pair::rightId).findFirst().orElseThrow(InvalidRequestException::new);
        return JsonNodeFactory.instance.objectNode().put("correct", expected.equals(check.rightId()));
    }

    private static ObjectNode hint(ExercisePreviewCommand command, ExercisePreviewCommand.Hint hint) {
        String letter = firstLetters(command).get(hint.blankId());
        if (letter == null) throw new InvalidRequestException();
        return JsonNodeFactory.instance.objectNode().put("blankId", hint.blankId().toString())
                .put("firstLetter", letter);
    }

    private static Map<UUID, String> firstLetters(ExercisePreviewCommand command) {
        return command.definition().key() instanceof AnswerKey.Cloze key
                ? LearnerContent.firstLetterHints(command.content(), key) : Map.of();
    }

    private static void sideIds(JsonNode side, Set<UUID> ids) {
        side.forEach(item -> ids.add(UUID.fromString(item.path("itemId").textValue())));
    }
}

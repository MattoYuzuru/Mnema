package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.ExerciseContent;
import app.mnema.learning.catalog.exercise.TextRule;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The self-evaluation of a generated exercise: the probes of {@code contracts/generation/exercises/README.md} run through
 * {@link AttemptEvaluation}, the evaluator behind {@code /exercise-previews}, exactly as a learner's answer would be. It is the
 * one public door to that package-private evaluator for the generation module, so the generator can never judge an exercise
 * by different rules than Study does.
 *
 * <p>The key as a response must be {@code CORRECT}; a distractor, a shifted or reversed arrangement must not. Like the preview,
 * the authored content is passed as the learner content (the evaluator only reads identifier sets and, for ORDER, the
 * block equivalence). A {@code SELF_CHECK} has no machine-checkable key and no probes.
 */
public final class ExerciseProbeEvaluator {
    private ExerciseProbeEvaluator() { }

    /** One probe and the result it must evaluate to ({@code CORRECT}, {@code PARTIAL} or {@code INCORRECT}). */
    public record Probe(String name, AttemptCommand.Response response, String expected) {
        public boolean isKey() { return name.startsWith("KEY"); }
    }

    /** A probe and what the evaluator answered ({@code ERROR} when the response did not even fit the exercise). */
    public record Outcome(Probe probe, String actual) {
        public boolean passed() { return probe.expected().equals(actual); }
    }

    /** The result name the evaluator gives {@code response} for {@code exercise}. */
    public static String evaluate(ExerciseCommand.Exercise exercise, AttemptCommand.Response response) {
        AttemptEvaluation.Subject subject = new AttemptEvaluation.Subject(exercise.type(), exercise.evaluatorPolicy(),
                exercise.answerKey(), exercise.content(), JsonNodeFactory.instance.objectNode(), Set.of(), false);
        try {
            AttemptEvaluation evaluation = AttemptEvaluation.evaluate(subject, response);
            return evaluation.result() == null ? evaluation.status().name() : evaluation.result().name();
        } catch (RuntimeException misfit) {
            return "ERROR";
        }
    }

    /** Runs every probe of {@code exercise}. */
    public static List<Outcome> run(ExerciseCommand.Exercise exercise) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Probe probe : probes(exercise)) outcomes.add(new Outcome(probe, evaluate(exercise, probe.response())));
        return outcomes;
    }

    /** The probes the server generates for the mechanic of {@code exercise}. */
    public static List<Probe> probes(ExerciseCommand.Exercise exercise) {
        AnswerKey key = AnswerKey.parse(exercise.type(), exercise.answerKey());
        return switch (key) {
            case AnswerKey.SelfReport ignored -> List.of();
            case AnswerKey.Text text -> freeResponse(text.rule());
            case AnswerKey.Cloze cloze -> cloze((ExerciseContent.Cloze) exercise.model(), cloze);
            case AnswerKey.Choice choice -> choice((ExerciseContent.Choice) exercise.model(), choice);
            case AnswerKey.Match match -> match(match);
            case AnswerKey.Order order -> order(order);
            case AnswerKey.Categorize categorize -> categorize((ExerciseContent.Categorize) exercise.model(), categorize);
        };
    }

    // ------------------------------------------------------------------------ mechanics

    private static List<Probe> freeResponse(TextRule rule) {
        List<Probe> probes = new ArrayList<>();
        for (int index = 0; index < rule.accepted().size(); index++) {
            probes.add(new Probe(index == 0 ? "KEY" : "KEY_ALTERNATIVE", new AttemptCommand.TextResponse(rule.accepted().get(index)),
                    "CORRECT"));
        }
        String neighbor = wrong(rule, rule.accepted().getFirst());
        if (neighbor != null) probes.add(new Probe("WRONG_NEIGHBOR", new AttemptCommand.TextResponse(neighbor), "INCORRECT"));
        probes.add(new Probe("EMPTY", new AttemptCommand.TextResponse(""), "INCORRECT"));
        return probes;
    }

    private static List<Probe> cloze(ExerciseContent.Cloze content, AnswerKey.Cloze key) {
        List<UUID> order = content.blanks().stream().map(ExerciseContent.Blank::blankId).toList();
        List<AttemptCommand.BlankText> right = new ArrayList<>();
        List<AttemptCommand.BlankText> wrong = new ArrayList<>();
        for (UUID blankId : order) {
            TextRule rule = key.blanks().stream().filter(blank -> blank.blankId().equals(blankId)).findFirst().orElseThrow().rule();
            right.add(new AttemptCommand.BlankText(blankId, rule.accepted().getFirst()));
            String neighbor = wrong(rule, rule.accepted().getFirst());
            wrong.add(new AttemptCommand.BlankText(blankId, neighbor == null ? "" : neighbor));
        }
        List<Probe> probes = new ArrayList<>();
        probes.add(new Probe("KEY", new AttemptCommand.ClozeResponse(right), "CORRECT"));
        if (right.size() >= 2) {
            List<AttemptCommand.BlankText> one = new ArrayList<>(right);
            one.set(one.size() - 1, wrong.getLast());
            probes.add(new Probe("ONE_BLANK_WRONG", new AttemptCommand.ClozeResponse(one), "PARTIAL"));
        }
        probes.add(new Probe("ALL_BLANKS_WRONG", new AttemptCommand.ClozeResponse(wrong), "INCORRECT"));
        return probes;
    }

    private static List<Probe> choice(ExerciseContent.Choice content, AnswerKey.Choice key) {
        List<Probe> probes = new ArrayList<>();
        probes.add(new Probe("KEY", new AttemptCommand.ChoiceResponse(key.correctOptionIds()), "CORRECT"));
        Set<UUID> correct = new HashSet<>(key.correctOptionIds());
        for (ExerciseContent.Option option : content.options()) {
            if (!correct.contains(option.optionId())) {
                probes.add(new Probe("DISTRACTOR", new AttemptCommand.ChoiceResponse(List.of(option.optionId())), "INCORRECT"));
            }
        }
        return probes;
    }

    private static List<Probe> match(AnswerKey.Match key) {
        List<AttemptCommand.MatchPair> right = new ArrayList<>();
        List<AttemptCommand.MatchPair> shifted = new ArrayList<>();
        int size = key.pairs().size();
        for (int index = 0; index < size; index++) {
            AnswerKey.Pair pair = key.pairs().get(index);
            right.add(new AttemptCommand.MatchPair(pair.leftId(), pair.rightId()));
            shifted.add(new AttemptCommand.MatchPair(pair.leftId(), key.pairs().get((index + 1) % size).rightId()));
        }
        return List.of(new Probe("KEY", new AttemptCommand.MatchResponse(right), "CORRECT"),
                new Probe("ALL_PAIRS_SHIFTED", new AttemptCommand.MatchResponse(shifted), "INCORRECT"));
    }

    private static List<Probe> order(AnswerKey.Order key) {
        List<UUID> reversed = new ArrayList<>(key.sequence());
        Collections.reverse(reversed);
        return List.of(new Probe("KEY", new AttemptCommand.OrderResponse(key.sequence()), "CORRECT"),
                new Probe("REVERSED", new AttemptCommand.OrderResponse(reversed), "INCORRECT"));
    }

    private static List<Probe> categorize(ExerciseContent.Categorize content, AnswerKey.Categorize key) {
        List<AttemptCommand.CategoryAssignment> right = new ArrayList<>();
        List<AttemptCommand.CategoryAssignment> moved = new ArrayList<>();
        List<UUID> categories = content.categories().stream().map(ExerciseContent.Category::categoryId).toList();
        for (AnswerKey.Assignment assignment : key.assignments()) {
            right.add(new AttemptCommand.CategoryAssignment(assignment.itemId(), assignment.categoryId()));
            int position = categories.indexOf(assignment.categoryId());
            moved.add(new AttemptCommand.CategoryAssignment(assignment.itemId(), categories.get((position + 1) % categories.size())));
        }
        return List.of(new Probe("KEY", new AttemptCommand.CategorizeResponse(right), "CORRECT"),
                new Probe("ALL_MOVED", new AttemptCommand.CategorizeResponse(moved), "INCORRECT"));
    }

    /** A near miss of {@code answer} (shortened or extended) that the rule does not accept; null when every attempt matches. */
    private static String wrong(TextRule rule, String answer) {
        List<String> candidates = new ArrayList<>();
        String[] words = answer.strip().split("\\s+");
        if (words.length > 1) candidates.add(String.join(" ", Arrays.copyOf(words, words.length - 1)));
        if (answer.length() > 1) candidates.add(answer.substring(0, answer.length() - 1));
        candidates.add(answer + "x");
        candidates.add("x" + answer);
        for (String candidate : candidates) {
            if (!candidate.isBlank() && !rule.matches(candidate)) return candidate;
        }
        return null;
    }
}

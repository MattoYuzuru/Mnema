package app.mnema.learning.generation.exercise;

/**
 * The stable finding codes of {@code contracts/generation/exercises/lint.json}, in phase order. {@link #rule()} is the
 * one-line rule the repair prompt shows the model; it never echoes content. The last three codes concern the server only:
 * {@code COMMAND_REJECTED} is a compiler or lint gap and {@code SELF_EVALUATION_*} a key the evaluator contradicts.
 */
public enum ExerciseCode {
    SCHEMA_INVALID(Phase.SCHEMA, "упражнение не соответствует схеме: точный набор полей, границы, перечисления, локальные идентификаторы"),
    REF_UNKNOWN_HANDLE(Phase.LINT, "handle блока материала (m1:b3) должен существовать в <material>"),
    REF_UNKNOWN_OBJECTIVE(Phase.LINT, "objective.ref должен быть одним из handle из <objectives>"),
    DUPLICATE_LOCAL_ID(Phase.LINT, "локальные идентификаторы внутри упражнения уникальны"),
    MECHANIC_NOT_ALLOWED(Phase.LINT, "механика не входит в разрешённый список из <task>"),
    SELF_CHECK_REFERENCE_BLANK(Phase.LINT, "эталон SELF_CHECK не должен быть пустым"),
    SELF_CHECK_REFERENCE_EQUALS_PROMPT(Phase.LINT, "эталон SELF_CHECK не должен повторять условие"),
    FREE_RESPONSE_ANSWER_IN_PROMPT(Phase.LINT, "правильный ответ не должен встречаться в условии"),
    FREE_RESPONSE_ALTERNATIVES_NOT_DISTINCT(Phase.LINT, "равнозначные ответы в accepted должны различаться"),
    FREE_RESPONSE_SOFT_EMPTY(Phase.LINT, "в SOFT ответ не должен состоять из одних знаков препинания"),
    CLOZE_KEY_BLANK_MISMATCH(Phase.LINT, "blanks описывает ровно те пропуски, что есть в passage, по одному разу"),
    CLOZE_FRAGMENT_NOT_IN_MATERIAL(Phase.LINT, "passage с подставленными ответами дословно встречается в одном блоке материала"),
    CLOZE_ANSWER_LENGTH_MISMATCH(Phase.LINT, "у пропуска ANSWER_LENGTH все варианты ответа одной длины"),
    CHOICE_OPTIONS_NOT_DISTINCT(Phase.LINT, "варианты ответа должны различаться"),
    CHOICE_CORRECT_COUNT(Phase.LINT, "SINGLE — ровно один верный вариант, MULTIPLE — хотя бы один"),
    CHOICE_ALL_OR_NONE_OF_THE_ABOVE(Phase.LINT, "без вариантов «все/ни один из перечисленных»"),
    MATCH_NOT_BIJECTION(Phase.LINT, "pairs — взаимно однозначное соответствие left и right: каждый элемент ровно один раз"),
    MATCH_LABELS_NOT_DISTINCT(Phase.LINT, "подписи слева различны, подписи справа различны"),
    MATCH_LABEL_LEAKS_PAIR(Phase.LINT, "подпись не содержит парную подпись: пара не должна угадываться"),
    ORDER_ITEMS_NOT_DISTINGUISHABLE(Phase.LINT, "в ORDER не меньше двух различных элементов"),
    CATEGORIZE_UNKNOWN_CATEGORY(Phase.LINT, "category элемента — id существующей категории"),
    CATEGORIZE_TOO_FEW_NON_EMPTY_CATEGORIES(Phase.LINT, "хотя бы в двух категориях есть элементы"),
    COMMAND_REJECTED(Phase.COMMAND, "упражнение не принято разбором публикации"),
    SELF_EVALUATION_KEY_NOT_CORRECT(Phase.SELF_EVALUATION, "верный ответ не распознан как верный"),
    SELF_EVALUATION_PROBE_ACCEPTED(Phase.SELF_EVALUATION, "заведомо неверный ответ принят как верный");

    /** The phases of {@code lint.json}, in the order the validator runs them. */
    public enum Phase { SCHEMA, LINT, COMMAND, SELF_EVALUATION }

    private final Phase phase;
    private final String rule;

    ExerciseCode(Phase phase, String rule) {
        this.phase = phase;
        this.rule = rule;
    }

    public Phase phase() { return phase; }

    /** One line for the repair prompt. */
    public String rule() { return rule; }
}

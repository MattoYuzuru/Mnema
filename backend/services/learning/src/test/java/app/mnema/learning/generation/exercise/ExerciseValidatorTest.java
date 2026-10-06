package app.mnema.learning.generation.exercise;

import app.mnema.learning.generation.exercise.ExerciseFixtures.GoldenIds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of the lint, the compiler and the self-evaluation that the committed fixtures do not pin one by one: boundaries of
 * the whole-word search, scripts without spaces, quoted material in a prompt, multiple choice, objective reuse by title, the
 * repair list and the re-pin checks. Each case is one exercise through the same {@link ExerciseValidator} the step uses.
 */
class ExerciseValidatorTest {
    private static final ExerciseOutputSchema SCHEMA = ExerciseOutputSchema.load();
    private static final ExerciseValidator VALIDATOR = new ExerciseValidator(SCHEMA);
    private static final UUID NODE_1 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID NODE_2 = UUID.fromString("00000000-0000-4000-8000-000000000004");

    private static ExerciseContext context(Set<String> allowed, Map<String, ExerciseContext.Objective> objectives) {
        Map<String, ExerciseContext.Block> blocks = new LinkedHashMap<>();
        blocks.put("b1", new ExerciseContext.Block(NODE_1, "The cat sat on the mat.\nThen it left."));
        blocks.put("b2", new ExerciseContext.Block(NODE_2, "Кошка сидит на коврике."));
        return new ExerciseContext(UUID.fromString("018f1d98-5c10-4abc-8abc-0123456789c1"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Map.of("m1", new ExerciseContext.Material(UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        UUID.fromString("55555555-5555-4555-8555-555555555555"), blocks)), objectives, allowed);
    }

    private static ExerciseContext context() {
        return context(ExerciseContext.ALL_MECHANICS, Map.of());
    }

    private static ExerciseValidator.Verdict validate(String exercise) {
        return validate(exercise, context());
    }

    private static ExerciseValidator.Verdict validate(String exercise, ExerciseContext context) {
        try {
            return VALIDATOR.validate(0, ExerciseFixtures.JSON.readTree(exercise), context, new GoldenIds());
        } catch (tools.jackson.core.JacksonException unreadable) {
            throw new IllegalArgumentException(unreadable);
        }
    }

    private static Set<String> codes(ExerciseValidator.Verdict verdict) {
        Set<String> codes = new TreeSet<>();
        if (verdict instanceof ExerciseValidator.Invalid invalid) invalid.findings().forEach(finding -> codes.add(finding.code().name()));
        return codes;
    }

    private static final String HEAD = "\"subject\":\"m1\",\"objective\":{\"title\":\"Кошка\"}";

    private static String choice(String mode, String... options) {
        return "{\"mechanic\":\"CHOICE\"," + HEAD + ",\"prompt\":[{\"kind\":\"TEXT\",\"text\":\"Выберите.\"}],\"selectionMode\":\"" + mode
                + "\",\"options\":[" + String.join(",", options) + "]}";
    }

    private static String right(String id, String text) {
        return "{\"id\":\"" + id + "\",\"text\":\"" + text + "\",\"correct\":true}";
    }

    private static String wrong(String id, String text) {
        return "{\"id\":\"" + id + "\",\"text\":\"" + text + "\",\"correct\":false,\"whyWrong\":\"Нет.\"}";
    }

    private static String freeResponse(String prompt, String answer) {
        return "{\"mechanic\":\"FREE_RESPONSE\"," + HEAD + ",\"prompt\":[" + prompt + "],\"accepted\":[\"" + answer + "\"],\"matchingMode\":\"STRICT\"}";
    }

    @Test
    void repeatedFreeResponseAlternativesAreDroppedWithAWarning() throws Exception {
        String exercise = "{\"mechanic\":\"FREE_RESPONSE\"," + HEAD + ",\"prompt\":[" + text("Как называется животное?")
                + "],\"accepted\":[\"Cat\",\" cat \",\"kitty\",\"CAT\"],\"matchingMode\":\"STRICT\"}";
        var verdict = validate(exercise);
        assertThat(verdict).isInstanceOf(ExerciseValidator.Valid.class);
        var accepted = ((ExerciseValidator.Valid) verdict).exercise();
        assertThat(accepted.warnings()).containsExactly("FREE_RESPONSE_ALTERNATIVE_DROPPED");
        assertThat(accepted.command().path("exercise").path("answerKey").path("accepted").toString()).isEqualTo("[\"Cat\",\"kitty\"]");
        // the lint alone still rejects the repeat: it is the guard behind the deterministic drop
        assertThat(ExerciseLint.lint(ExerciseFixtures.JSON.readTree(exercise), context()).stream().map(f -> f.code().name()))
                .contains("FREE_RESPONSE_ALTERNATIVES_NOT_DISTINCT");
        assertThat(((ExerciseValidator.Valid) validate(freeResponse(text("Как называется животное?"), "cat"))).exercise().warnings()).isEmpty();
    }

    private static String text(String value) {
        return "{\"kind\":\"TEXT\",\"text\":\"" + value + "\"}";
    }

    // ------------------------------------------------------------------------- CHOICE

    @Test
    void multipleChoiceNeedsOneCorrectOptionAndMayHaveSeveral() {
        assertThat(validate(choice("MULTIPLE", right("o1", "Кошка"), right("o2", "Кот"), wrong("o3", "Собака")))).isInstanceOf(ExerciseValidator.Valid.class);
        assertThat(codes(validate(choice("MULTIPLE", wrong("o1", "Кошка"), wrong("o2", "Кот"))))).containsExactly("CHOICE_CORRECT_COUNT");
        assertThat(codes(validate(choice("SINGLE", right("o1", "Кошка"), right("o2", "Кот"))))).containsExactly("CHOICE_CORRECT_COUNT");
    }

    @Test
    void optionsAreDistinctAfterCaseAndWhitespaceAndNoOptionIsAllOrNoneOfTheAbove() {
        assertThat(codes(validate(choice("SINGLE", right("o1", "Кошка  спит"), wrong("o2", "кошка спит"))))).containsExactly("CHOICE_OPTIONS_NOT_DISTINCT");
        assertThat(codes(validate(choice("SINGLE", right("o1", "Cat"), wrong("o2", "None of the above"))))).containsExactly("CHOICE_ALL_OR_NONE_OF_THE_ABOVE");
        assertThat(codes(validate(choice("SINGLE", right("o1", "Cat"), wrong("o2", "ALL OF THE ABOVE!"))))).containsExactly("CHOICE_ALL_OR_NONE_OF_THE_ABOVE");
        // a word that merely contains the phrase's letters is not the phrase
        assertThat(validate(choice("SINGLE", right("o1", "Cat"), wrong("o2", "Hall of the aboveground"))))
                .isInstanceOf(ExerciseValidator.Valid.class);
    }

    // --------------------------------------------------------------------- FREE_RESPONSE

    @Test
    void anAnswerInThePromptIsFoundAsAWholeWordNotAsAPartOfAnotherWord() {
        assertThat(codes(validate(freeResponse(text("The cat sat."), "cat")))).containsExactly("FREE_RESPONSE_ANSWER_IN_PROMPT");
        assertThat(codes(validate(freeResponse(text("The CAT sat."), "Cat")))).containsExactly("FREE_RESPONSE_ANSWER_IN_PROMPT");
        assertThat(validate(freeResponse(text("A category of animals."), "cat"))).isInstanceOf(ExerciseValidator.Valid.class);
    }

    @Test
    void ascriptWithoutSpacesIsSearchedAsASubstring() {
        assertThat(codes(validate(freeResponse(text("这只猫很可爱，它叫什么？"), "猫")))).containsExactly("FREE_RESPONSE_ANSWER_IN_PROMPT");
        assertThat(validate(freeResponse(text("这只动物很可爱，它叫什么？"), "猫"))).isInstanceOf(ExerciseValidator.Valid.class);
    }

    @Test
    void aQuotedMaterialBlockInThePromptCountsAsPromptText() {
        String quoted = "{\"kind\":\"MATERIAL\",\"ref\":\"m1:b1\"}";
        assertThat(codes(validate(freeResponse(quoted, "mat")))).containsExactly("FREE_RESPONSE_ANSWER_IN_PROMPT");
        assertThat(validate(freeResponse(quoted, "dog"))).isInstanceOf(ExerciseValidator.Valid.class);
    }

    // ------------------------------------------------------------------------- CLOZE

    private static String cloze(String passage, String blanks) {
        return "{\"mechanic\":\"CLOZE\"," + HEAD + ",\"prompt\":[" + text("Заполните.") + "],\"passage\":[" + passage + "],\"blanks\":[" + blanks + "]}";
    }

    private static String blank(String id) {
        return "{\"kind\":\"BLANK\",\"blank\":\"" + id + "\",\"size\":{\"mode\":\"ANSWER_LENGTH\"},\"firstLetterHint\":false}";
    }

    private static String key(String id, String answer) {
        return "{\"blank\":\"" + id + "\",\"accepted\":[\"" + answer + "\"],\"matchingMode\":\"STRICT\"}";
    }

    @Test
    void aPassageMayCrossALineBreakOfTheBlockBecauseWhitespaceIsCollapsed() {
        // the block reads "The cat sat on the mat.\nThen it left." (a paragraph break); the learner-facing passage uses a space
        assertThat(validate(cloze(text("The cat sat on the mat. Then it ") + "," + blank("bl1") + "," + text("."), key("bl1", "left"))))
                .isInstanceOf(ExerciseValidator.Valid.class);
        assertThat(codes(validate(cloze(text("The cat sat on the ") + "," + blank("bl1") + "," + text("."), key("bl1", "rug")))))
                .containsExactly("CLOZE_FRAGMENT_NOT_IN_MATERIAL");
    }

    @Test
    void aBlankUsedTwiceInOnePassageIsADuplicateLocalId() {
        String passage = text("The ") + "," + blank("bl1") + "," + text(" sat on the ") + "," + blank("bl1") + "," + text(".");
        assertThat(codes(validate(cloze(passage, key("bl1", "cat"))))).contains("DUPLICATE_LOCAL_ID");
    }

    // -------------------------------------------------------------------- MATCH, ORDER, CATEGORIZE

    private static String match(String left, String right, String pairs) {
        return "{\"mechanic\":\"MATCH\"," + HEAD + ",\"prompt\":[" + text("Соедините.") + "],\"left\":[" + left + "],\"right\":[" + right
                + "],\"pairs\":[" + pairs + "]}";
    }

    private static String item(String id, String text) {
        return "{\"id\":\"" + id + "\",\"text\":\"" + text + "\"}";
    }

    @Test
    void aPairThatReferencesAnUnknownItemIsNotABijectionAndTheLeakIsFoundInEitherDirection() {
        String left = item("l1", "Cat") + "," + item("l2", "Dog");
        String right = item("r1", "Meows") + "," + item("r2", "Barks");
        assertThat(codes(validate(match(left, right, "{\"left\":\"l1\",\"right\":\"r1\"},{\"left\":\"l9\",\"right\":\"r2\"}"))))
                .containsExactly("MATCH_NOT_BIJECTION");
        String longLeft = item("l1", "The cat sat") + "," + item("l2", "Dog");
        String shortRight = item("r1", "cat") + "," + item("r2", "Barks");
        assertThat(codes(validate(match(longLeft, shortRight, "{\"left\":\"l1\",\"right\":\"r1\"},{\"left\":\"l2\",\"right\":\"r2\"}"))))
                .containsExactly("MATCH_LABEL_LEAKS_PAIR");
    }

    @Test
    void orderItemsThatDifferOnlyByCaseAreNotDistinguishableAndAPalindromeFailsTheReversalProbe() {
        String prompt = "\"prompt\":[" + text("Расставьте.") + "]";
        String same = "{\"mechanic\":\"ORDER\"," + HEAD + "," + prompt + ",\"items\":[" + item("i1", "Шаг") + "," + item("i2", "шаг") + "]}";
        assertThat(codes(validate(same))).containsExactly("ORDER_ITEMS_NOT_DISTINGUISHABLE");
        // A, B, A reversed is A, B, A: every arrangement the learner could pick that equals the key is the key, so the order is ambiguous
        String palindrome = "{\"mechanic\":\"ORDER\"," + HEAD + "," + prompt + ",\"items\":[" + item("i1", "Начало") + "," + item("i2", "Середина")
                + "," + item("i3", "Начало") + "]}";
        assertThat(codes(validate(palindrome))).containsExactly("SELF_EVALUATION_PROBE_ACCEPTED");
    }

    @Test
    void anUnknownCategoryIsReportedAloneAndNotAsTooFewNonEmptyCategories() {
        String categories = "{\"id\":\"c1\",\"label\":\"А\"},{\"id\":\"c2\",\"label\":\"Б\"}";
        String items = "{\"id\":\"i1\",\"text\":\"x1\",\"category\":\"c1\"},{\"id\":\"i2\",\"text\":\"x2\",\"category\":\"c7\"}";
        String exercise = "{\"mechanic\":\"CATEGORIZE\"," + HEAD + ",\"prompt\":[" + text("Разнесите.") + "],\"categories\":[" + categories
                + "],\"items\":[" + items + "]}";
        assertThat(codes(validate(exercise))).containsExactly("CATEGORIZE_UNKNOWN_CATEGORY");
    }

    // ----------------------------------------------------------------- schema and findings

    @Test
    void aSchemaFindingNamesPathsAndNeverEchoesContent() {
        ExerciseValidator.Verdict verdict = validate("{\"mechanic\":\"CHOICE\",\"subject\":\"m1\",\"objective\":{\"title\":\"СЕКРЕТ-в-заголовке\"},"
                + "\"prompt\":[],\"selectionMode\":\"SINGLE\",\"options\":[]}");
        ExerciseFinding finding = ((ExerciseValidator.Invalid) verdict).findings().getFirst();
        assertThat(finding.code()).isEqualTo(ExerciseCode.SCHEMA_INVALID);
        assertThat(finding.path()).contains("$.prompt").contains("$.options").doesNotContain("СЕКРЕТ");
        assertThat(codes(validate("{\"mechanic\":\"TELEPATHY\",\"subject\":\"m1\"}"))).containsExactly("SCHEMA_INVALID");
        assertThat(((ExerciseValidator.Invalid) validate("{\"mechanic\":\"TELEPATHY\"}")).findings().getFirst().path()).isEqualTo("$.mechanic");
    }

    @Test
    void theRootOfAnAnswerIsAnObjectWithOneToTwentyExercises() throws Exception {
        assertThat(SCHEMA.rootShape(ExerciseFixtures.JSON.readTree("{\"exercises\":[{}]}"))).isTrue();
        assertThat(SCHEMA.rootShape(ExerciseFixtures.JSON.readTree("{\"exercises\":[]}"))).isFalse();
        assertThat(SCHEMA.rootShape(ExerciseFixtures.JSON.readTree("{\"exercises\":[{}],\"extra\":1}"))).isFalse();
        assertThat(SCHEMA.rootShape(ExerciseFixtures.JSON.readTree("{\"exercises\":{}}"))).isFalse();
        StringBuilder many = new StringBuilder("{\"exercises\":[");
        for (int index = 0; index < 21; index++) many.append(index == 0 ? "{}" : ",{}");
        assertThat(SCHEMA.rootShape(ExerciseFixtures.JSON.readTree(many + "]}"))).isFalse();
    }

    @Test
    void aSchemaKeywordTheValidatorDoesNotImplementIsRefusedLoudly() throws Exception {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ExerciseOutputSchema.of(ExerciseFixtures.JSON.readTree("{\"type\":\"string\",\"format\":\"uuid\"}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("format");
    }

    @Test
    void theRepairListLeadsWithTheInstructionNamesCodesAndPathsOnlyAndStaysBounded() {
        String list = ExerciseRepairList.format(List.of(new ExerciseFinding(1, ExerciseCode.CHOICE_CORRECT_COUNT, "options"),
                new ExerciseFinding(-1, ExerciseCode.SCHEMA_INVALID, "exercises")), 2);
        assertThat(list).startsWith("Верни json").contains("ровно из 2 упражнений").contains("упражнение 2: CHOICE_CORRECT_COUNT (options)")
                .contains("ответ: SCHEMA_INVALID (exercises)").contains("CHOICE_CORRECT_COUNT: SINGLE");
        List<ExerciseFinding> many = new java.util.ArrayList<>();
        for (int index = 0; index < 400; index++) many.add(new ExerciseFinding(index, ExerciseCode.MATCH_LABEL_LEAKS_PAIR, "pairs"));
        assertThat(ExerciseRepairList.format(many, 400)).hasSizeLessThanOrEqualTo(ExerciseRepairList.MAX_CHARACTERS).startsWith("Верни json");
    }

    @Test
    void entitiesAndPlaceholdersCopiedFromThePromptAreNormalizedBeforeTheLint() {
        Map<String, ExerciseContext.Block> blocks = new LinkedHashMap<>();
        blocks.put("b1", new ExerciseContext.Block(NODE_1, "Планировщик \"выбирает\" план & <стоимость>. Пишите на ivan@example.com."));
        ExerciseContext special = new ExerciseContext(UUID.fromString("018f1d98-5c10-4abc-8abc-0123456789c1"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Map.of("m1", new ExerciseContext.Material(UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        UUID.fromString("55555555-5555-4555-8555-555555555555"), blocks)), Map.of(), ExerciseContext.ALL_MECHANICS);
        // the fragments as the model copies them from the escaped prompt
        assertThat(validate(cloze(text("Планировщик &quot;выбирает&quot; план &amp; &lt;") + "," + blank("bl1") + "," + text("&gt;."), key("bl1", "стоимость")),
                special)).isInstanceOf(ExerciseValidator.Valid.class);
        assertThat(validate(cloze(text("Пишите на ") + "," + blank("bl1") + "," + text("."), key("bl1", "ivan@example.com")), special))
                .isInstanceOf(ExerciseValidator.Valid.class);
        assertThat(validate(cloze(text("Пишите на [email]") + "," + blank("bl1") + "," + text("."), key("bl1", "ivan@example.com")), special))
                .isInstanceOf(ExerciseValidator.Invalid.class);
        // a free response whose accepted answer is a decoded form of the entity text is accepted, not leaked
        assertThat(validate(freeResponse(text("Какой оператор планировщик ставит перед стоимостью?"), "&amp; &lt;"), special))
                .isInstanceOf(ExerciseValidator.Valid.class);
    }

    @Test
    void aSchemaFindingNeverEchoesAPropertyNameTheModelInvented() {
        ExerciseValidator.Verdict verdict = validate("{\"mechanic\":\"CHOICE\",\"subject\":\"m1\",\"objective\":{\"title\":\"Кошка\"},"
                + "\"prompt\":[" + text("Выберите.") + "],\"selectionMode\":\"SINGLE\",\"options\":[" + right("o1", "Да") + "," + wrong("o2", "Нет")
                + "],\"СЕКРЕТНОЕ-свойство\":1}");
        assertThat(((ExerciseValidator.Invalid) verdict).findings()).extracting(ExerciseFinding::path)
                .allSatisfy(path -> assertThat(path).doesNotContain("СЕКРЕТНОЕ"));
    }

    @Test
    void theRepairListNamesWhatIsKeptAndDropsADuplicateLine() {
        String list = ExerciseRepairList.format(List.of(new ExerciseFinding(1, ExerciseCode.DUPLICATE_EXERCISE, "prompt")), 1,
                List.of("CHOICE · Первый вопрос", "CHOICE · " + "x".repeat(200)));
        assertThat(list).contains("Уже приняты, не повторяй их:").contains("- CHOICE · Первый вопрос").contains("упражнение 2: DUPLICATE_EXERCISE (prompt)")
                .doesNotContain("x".repeat(100));
    }

    // --------------------------------------------------------------------------- objectives

    @Test
    void anObjectiveIsReusedByRefAndByTitleAfterNormalizationAndOtherwiseCreated() {
        UUID objective = UUID.fromString("77777777-7777-4777-8777-777777777771");
        UUID revision = UUID.fromString("77777777-7777-4777-8777-777777777773");
        ExerciseContext offered = context(ExerciseContext.ALL_MECHANICS, Map.of("t1", new ExerciseContext.Objective(objective, revision, "Выбор плана")));
        for (String spelling : List.of("{\"ref\":\"t1\"}", "{\"title\":\"Выбор плана\"}", "{\"title\":\"  ВЫБОР   плана \"}")) {
            ObjectNode command = accepted(validateWith(spelling, offered));
            assertThat(command.path("objective").path("operation").stringValue(null)).as(spelling).isEqualTo("reuse");
            assertThat(command.path("objective").path("objectiveId").stringValue(null)).isEqualTo(objective.toString());
        }
        ObjectNode created = accepted(validateWith("{\"title\":\"Выбор индекса\"}", offered));
        assertThat(created.path("objective").path("operation").stringValue(null)).isEqualTo("create");
        assertThat(created.path("objective").path("title").stringValue(null)).isEqualTo("Выбор индекса");
        assertThat(codes(validateWith("{\"ref\":\"t9\"}", offered))).containsExactly("REF_UNKNOWN_OBJECTIVE");
    }

    private static ExerciseValidator.Verdict validateWith(String objective, ExerciseContext context) {
        String exercise = "{\"mechanic\":\"SELF_CHECK\",\"subject\":\"m1\",\"objective\":" + objective + ",\"prompt\":[" + text("Расскажите.")
                + "],\"reference\":[{\"kind\":\"MATERIAL\",\"ref\":\"m1:b2\"}]}";
        return validate(exercise, context);
    }

    private static ObjectNode accepted(ExerciseValidator.Verdict verdict) {
        assertThat(verdict).isInstanceOf(ExerciseValidator.Valid.class);
        return ((ExerciseValidator.Valid) verdict).exercise().command();
    }

    @Test
    void theArtifactTitleIsTheFirstTextOfThePromptElseTheObjectiveAndAtMost240CodePoints() throws Exception {
        JsonNode exercise = ExerciseFixtures.JSON.readTree("{\"content\":{\"prompt\":[{\"kind\":\"MATERIAL\"},{\"kind\":\"TEXT\",\"text\":\" Вопрос? \"}]}}");
        assertThat(ExerciseValidator.title(exercise, "Цель")).isEqualTo("Вопрос?");
        assertThat(ExerciseValidator.title(ExerciseFixtures.JSON.readTree("{\"content\":{\"prompt\":[]}}"), "Цель")).isEqualTo("Цель");
        String long240 = "я".repeat(300);
        JsonNode longPrompt = ExerciseFixtures.JSON.readTree("{\"content\":{\"prompt\":[{\"kind\":\"TEXT\",\"text\":\"" + long240 + "\"}]}}");
        assertThat(ExerciseValidator.title(longPrompt, "x").codePointCount(0, ExerciseValidator.title(longPrompt, "x").length())).isEqualTo(240);
        String astral = "😀".repeat(300);
        JsonNode emoji = ExerciseFixtures.JSON.readTree("{\"content\":{\"prompt\":[{\"kind\":\"TEXT\",\"text\":\"" + astral + "\"}]}}");
        String title = ExerciseValidator.title(emoji, "x");
        assertThat(title.codePointCount(0, title.length())).isEqualTo(240);
    }

    // ------------------------------------------------------------------------------ re-pin

    @Test
    void aCompiledExerciseIsCheckedAgainstTheNewTextOfTheMaterialAfterARepin() throws Exception {
        ExerciseValidator.Accepted accepted = ((ExerciseValidator.Valid) validate(freeResponse("{\"kind\":\"MATERIAL\",\"ref\":\"m1:b2\"}", "собака"))).exercise();
        // the quoted block still lacks the answer, so the re-pin is fine
        assertThat(VALIDATOR.revalidate(accepted.command(), node -> java.util.Optional.of("Кошка лежит"), List.of("Кошка лежит"))).isEmpty();
        // the new text of the quoted block now contains the answer: the question would give itself away
        assertThat(VALIDATOR.revalidate(accepted.command(), node -> java.util.Optional.of("Собака лает"), List.of("Собака лает")))
                .extracting(finding -> finding.code().name()).containsExactly("FREE_RESPONSE_ANSWER_IN_PROMPT");
        // the quoted node is gone
        assertThat(VALIDATOR.revalidate(accepted.command(), node -> java.util.Optional.empty(), List.of()))
                .extracting(finding -> finding.code().name()).containsExactly("REF_UNKNOWN_HANDLE");
    }

    @Test
    void aClozePassageMustStillBeAFragmentOfTheNewMaterial() {
        String passage = text("The cat sat on the ") + "," + blank("bl1") + "," + text(".");
        ExerciseValidator.Accepted accepted = ((ExerciseValidator.Valid) validate(cloze(passage, key("bl1", "mat")))).exercise();
        assertThat(VALIDATOR.revalidate(accepted.command(), node -> java.util.Optional.empty(), List.of("The cat sat on the mat."))).isEmpty();
        assertThat(VALIDATOR.revalidate(accepted.command(), node -> java.util.Optional.empty(), List.of("A dog barked.")))
                .extracting(finding -> finding.code().name()).containsExactly("CLOZE_FRAGMENT_NOT_IN_MATERIAL");
    }
}

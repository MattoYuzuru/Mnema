package app.mnema.learning.generation.exercise;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the prompt did to the material is undone exactly, and only what was done. */
class ModelStringsTest {
    private static final List<String> BLOCKS = List.of("Пишите на ivan@example.com или звоните. Планировщик \"выбирает\" план & <стоимость>.");

    @Test
    void exactlyTheFourEntitiesOfThePromptAreDecodedInOnePass() {
        assertThat(ModelStrings.text("&quot;a&quot; &amp; &lt;b&gt;", List.of())).isEqualTo("\"a\" & <b>");
        assertThat(ModelStrings.text("&amp;lt;", List.of())).isEqualTo("&lt;");
        assertThat(ModelStrings.text("&apos; &#39; &nbsp; &copy; & x;", List.of())).isEqualTo("&apos; &#39; &nbsp; &copy; & x;");
        assertThat(ModelStrings.text("без сущностей", List.of())).isEqualTo("без сущностей");
    }

    @Test
    void aPlaceholderIsRestoredOnlyByAnExactMatchOfARawBlock() {
        assertThat(ModelStrings.text("Пишите на [email] или звоните.", BLOCKS)).isEqualTo("Пишите на ivan@example.com или звоните.");
        // not in the material: the placeholder stays and the lint fails it like any other invented text
        assertThat(ModelStrings.text("Пишите на [email] или молчите.", BLOCKS)).isEqualTo("Пишите на [email] или молчите.");
        assertThat(ModelStrings.text("Звоните по [phone] завтра", BLOCKS)).isEqualTo("Звоните по [phone] завтра");
    }

    @Test
    void aRestoredFragmentIsOnlyTakenWhenRedactingItGivesTheStringBack() {
        // "ivan@example.com или" is a fragment of the block, but redacting it does not give "[email] или" with a different tail
        assertThat(ModelStrings.text("[email] и звоните", BLOCKS)).isEqualTo("[email] и звоните");
    }
}

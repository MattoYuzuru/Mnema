package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeckVisibilityTest {
    /** Openness, most open first: the product contract rotates the link on ANY step down this ladder. */
    private static final DeckVisibility[] LADDER = {DeckVisibility.PUBLIC, DeckVisibility.LINK, DeckVisibility.INVITE, DeckVisibility.PRIVATE};

    @Test
    void everyStepToAMoreRestrictiveLevelRotatesTheCode() {
        for (int from = 0; from < LADDER.length; from++) {
            for (int to = from + 1; to < LADDER.length; to++) {
                assertThat(LADDER[from].lowersTo(LADDER[to])).as(LADDER[from] + " -> " + LADDER[to]).isTrue();
            }
        }
        assertThat(DeckVisibility.PUBLIC.lowersTo(DeckVisibility.LINK)).isTrue();
    }

    @Test
    void raisingTheLevelOrStayingPutKeepsTheCode() {
        for (int from = 0; from < LADDER.length; from++) {
            for (int to = 0; to <= from; to++) {
                assertThat(LADDER[from].lowersTo(LADDER[to])).as(LADDER[from] + " -> " + LADDER[to]).isFalse();
            }
        }
    }

    @Test
    void theDeclarationOrderIsTheOpennessOrder() {
        assertThat(DeckVisibility.values()).containsExactly(DeckVisibility.PRIVATE, DeckVisibility.INVITE, DeckVisibility.LINK, DeckVisibility.PUBLIC);
    }
}

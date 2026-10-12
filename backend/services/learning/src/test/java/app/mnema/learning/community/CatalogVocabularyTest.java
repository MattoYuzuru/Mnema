package app.mnema.learning.community;

import app.mnema.learning.catalog.exercise.ExerciseType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Community/1 (#448): the mapping of exercise types and MIME types to the facets of the catalogue lives in two enums; this is its specification. */
class CatalogVocabularyTest {
    @Test
    void everyExerciseTypeMapsToTheDocumentedMechanicBits() {
        assertThat(CatalogMechanic.maskOf(ExerciseType.SELF_CHECK, false)).as("a flashcard is the default, not a facet").isZero();
        assertThat(CatalogMechanic.maskOf(ExerciseType.MATCH, false)).isEqualTo(2);
        assertThat(CatalogMechanic.maskOf(ExerciseType.CATEGORIZE, false)).isEqualTo(2);
        assertThat(CatalogMechanic.maskOf(ExerciseType.FREE_RESPONSE, false)).isEqualTo(4);
        assertThat(CatalogMechanic.maskOf(ExerciseType.CLOZE, false)).isEqualTo(4);
        assertThat(CatalogMechanic.maskOf(ExerciseType.CHOICE, false)).isEqualTo(8);
        assertThat(CatalogMechanic.maskOf(ExerciseType.ORDER, false)).isEqualTo(16);
        // audio is a property of the content: it adds «на слух» to whatever the mechanic is, a flashcard included
        assertThat(CatalogMechanic.maskOf(ExerciseType.SELF_CHECK, true)).isEqualTo(1);
        assertThat(CatalogMechanic.maskOf(ExerciseType.CLOZE, true)).isEqualTo(5);
        assertThat(CatalogMechanic.maskOf(ExerciseType.ORDER, true)).isEqualTo(17);
    }

    @Test
    void theBitsAreDistinctPowersOfTwoThatFillTheColumnCheck() {
        int all = 0;
        for (CatalogMechanic mechanic : CatalogMechanic.values()) {
            assertThat(Integer.bitCount(mechanic.bit())).isOne();
            assertThat(all & mechanic.bit()).isZero();
            all |= mechanic.bit();
        }
        assertThat(all).isEqualTo(CatalogMechanic.ALL).isEqualTo(31);
        int media = 0;
        for (CatalogMedia kind : CatalogMedia.values()) {
            assertThat(Integer.bitCount(kind.bit())).isOne();
            media |= kind.bit();
        }
        assertThat(media).isEqualTo(CatalogMedia.ALL).isEqualTo(7);
        assertThat(CatalogMechanic.fromMask(CatalogMechanic.ALL)).containsExactlyInAnyOrder(CatalogMechanic.values());
        assertThat(CatalogMechanic.fromMask(0)).isEmpty();
        assertThat(CatalogMedia.fromMask(5)).containsExactlyInAnyOrder(CatalogMedia.IMAGE, CatalogMedia.VIDEO);
    }

    @Test
    void theMediaKindIsTheTopLevelTypeOfTheMimeType() {
        assertThat(CatalogMedia.maskOf(Set.of("image"))).isEqualTo(1);
        assertThat(CatalogMedia.maskOf(Set.of("audio", "video"))).isEqualTo(6);
        assertThat(CatalogMedia.maskOf(Set.of("IMAGE", "application", "font"))).as("case is folded; a document or a font is no facet").isEqualTo(1);
        assertThat(CatalogMedia.maskOf(Set.of())).isZero();
    }

    @Test
    void settingsAreBoundedAndFailAtStartup() {
        CatalogSettings defaults = new CatalogSettings(500, 200);
        assertThat(defaults.sliceSize).isEqualTo(500);
        assertThat(defaults.rebuildSize).isEqualTo(200);
        assertThat(new CatalogSettings(1, 1).sliceSize).isOne();
        assertThat(new CatalogSettings(2_000, 1_000).rebuildSize).isEqualTo(1_000);
        for (int[] invalid : new int[][] {{0, 200}, {2_001, 200}, {500, 0}, {500, 1_001}, {-1, -1}}) {
            assertThatThrownBy(() -> new CatalogSettings(invalid[0], invalid[1])).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

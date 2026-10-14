package app.mnema.learning.community;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The display rule of the audience numbers against the constants of {@code contracts/community/catalog.json}. */
class AudienceCountsTest {
    @ParameterizedTest
    @CsvSource({"0,", "1,", "9,", "10,10", "11,11", "42,42", "99,99", "100,100", "101,100", "109,100", "110,110", "999,990", "1000,1000", "1099,1000", "1234,1200",
            "9999,9900", "10000,10000", "12345,12000", "99999,99000", "100999,100000", "2147483647,2100000000"})
    void belowTheMinimumNothingIsShownAndAboveItTheCountIsFlooredToTwoSignificantDigits(int raw, Integer shown) {
        assertThat(AudienceCounts.shown(raw)).isEqualTo(shown);
    }

    @Test
    void aNegativeCountIsADefect() {
        assertThatThrownBy(() -> AudienceCounts.shown(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConstantsAreTheOnesOfTheSharedContractFile() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/community/catalog.json"))) root = root.getParent();
        assertThat(root).isNotNull();
        var constants = JsonMapper.builder().build().readTree(Files.readString(root.resolve("contracts/community/catalog.json"))).path("constants");
        assertThat(AudienceCounts.MIN_SHOWN).isEqualTo(constants.path("audienceMinShown").intValue());
        assertThat(constants.path("audienceRounding").stringValue(null)).startsWith("floor to two significant digits (1234 -> 1200, 99 -> 99)");
        // the contract's own examples
        assertThat(AudienceCounts.shown(1234)).isEqualTo(1200);
        assertThat(AudienceCounts.shown(99)).isEqualTo(99);
        assertThat(AudienceCounts.shown(AudienceCounts.MIN_SHOWN - 1)).isNull();
    }
}

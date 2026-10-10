package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PublicCodesTest {
    @Test
    void aCodeIsTenBase58CharactersWithoutTheAmbiguousOnes() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 5_000; index++) {
            String code = PublicCodes.next(random);
            assertThat(code).hasSize(10).matches("[1-9A-HJ-NP-Za-km-z]{10}").doesNotContain("0", "O", "I", "l");
            assertThat(PublicCodes.valid(code)).isTrue();
            seen.add(code);
        }
        assertThat(seen).hasSize(5_000);
    }

    @Test
    void anythingThatIsNotACodeShapeIsInvalidWithoutALookup() {
        for (String value : new String[] {null, "", "short", "0123456789", "abcdefghijk", "abcdefghi", "ABCDEFGHIJ", "abc defghi", "abcdefghi\n",
                "abcdefghi!", "../../etc/", "abcdefghiО"}) {
            assertThat(PublicCodes.valid(value)).as(String.valueOf(value)).isFalse();
        }
        assertThat(PublicCodes.valid("abcdefghij")).isTrue();
    }
}

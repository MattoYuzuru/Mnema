package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InviteTokensTest {
    @Test
    void aTokenIs32RandomBytesInUnpaddedBase64UrlAndAlwaysWellFormed() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < 500; index++) {
            String token = InviteTokens.generate(random);
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
            assertThat(InviteTokens.wellFormed(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void onlyAnExactEncodingOf32BytesIsWellFormed() {
        String token = InviteTokens.generate(new SecureRandom());
        assertThat(InviteTokens.wellFormed(null)).isFalse();
        assertThat(InviteTokens.wellFormed("")).isFalse();
        assertThat(InviteTokens.wellFormed(token + "A")).isFalse();
        assertThat(InviteTokens.wellFormed(token.substring(1))).isFalse();
        assertThat(InviteTokens.wellFormed(token.substring(0, 42) + "+")).isFalse();
        assertThat(InviteTokens.wellFormed(token.substring(0, 42) + "=")).isFalse();
        // the last character carries only 4 bits, so 'B' (and 'C', 'D') is never the canonical spelling of any 32 bytes
        assertThat(InviteTokens.wellFormed(token.substring(0, 42) + "B")).isFalse();
    }

    @Test
    void theDigestIsSha256AndDiffersPerToken() {
        byte[] first = InviteTokens.hash("a".repeat(43));
        assertThat(first).hasSize(32);
        assertThat(InviteTokens.hash("a".repeat(43))).isEqualTo(first);
        assertThat(InviteTokens.hash("b".repeat(43))).isNotEqualTo(first);
    }
}

package app.mnema.identityaccount.profile;

import app.mnema.identityaccount.contract.AccountFailure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileBioTest {
    @Test
    void preservesUnicodeAndParagraphsWithoutExcessiveWhitespace() {
        assertThat(ProfileBio.normalize("\r\n  Учусь\t\tкаждый  день ✨  \r\n\r\n \t\r\n\r\n  • Математика\u00a0\u00a0и языки  \r\n"))
                .isEqualTo("Учусь каждый день ✨\n\n• Математика и языки");
        assertThat(ProfileBio.normalize("Первая" + "\n".repeat(100) + "Вторая")).isEqualTo("Первая\n\nВторая");
        assertThat(ProfileBio.normalize(" \t\n\n\n ")).isEmpty();
        assertThat(ProfileBio.normalize("")).isEmpty();
        assertThat(ProfileBio.normalize("Первая\rВторая")).isEqualTo("Первая\nВторая");
    }

    @Test
    void enforcesCharacterAndLineBoundsWithoutTruncatingContent() {
        assertThat(ProfileBio.normalize("а".repeat(200))).hasSize(200);
        assertThat(ProfileBio.normalize(String.join("\n", java.util.Collections.nCopies(6, "строка"))))
                .isEqualTo(String.join("\n", java.util.Collections.nCopies(6, "строка")));
        for (String value : new String[]{null, "а".repeat(201),
                String.join("\n", java.util.Collections.nCopies(7, "строка")),
                "Текст\u0000", "Текст\u0001", "Текст\u000b", "Текст\u001b", "Текст\u007f"}) {
            assertThatThrownBy(() -> ProfileBio.normalize(value)).isInstanceOfSatisfying(AccountFailure.class, failure -> {
                assertThat(failure.status()).isEqualTo(400);
                assertThat(failure.code()).isEqualTo("invalid_profile_bio");
            });
        }
    }
}

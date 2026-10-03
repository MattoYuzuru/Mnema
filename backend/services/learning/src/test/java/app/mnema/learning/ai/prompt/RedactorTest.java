package app.mnema.learning.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {
    @Test
    void emailsAreRedacted() {
        assertThat(Redactor.redact("пишите на ivan.petrov+study@example.co.uk или anna@пример.рф сегодня"))
                .isEqualTo("пишите на [email] или [email] сегодня");
    }

    @Test
    void cardNumbersThatPassLuhnAreRedactedWithAnySeparators() {
        assertThat(Redactor.redact("карта 4111 1111 1111 1111, ещё 5500-0000-0000-0004 и 378282246310005"))
                .isEqualTo("карта [card], ещё [card] и [card]");
    }

    @Test
    void aCardGluedToOtherNumbersOrRepeatedIsStillFound() {
        assertThat(Redactor.redact("тел +7 916 123-45-67 4111 1111 1111 1111 конец")).contains("[card]").doesNotContain("4111")
                .contains("[phone]");
        assertThat(Redactor.redact("4111 1111 1111 1111 5500 0000 0000 0004")).isEqualTo("[card] [card]");
        assertThat(Redactor.redact("номера 4111111111111111, 5500000000000004.")).isEqualTo("номера [card], [card].");
    }

    @Test
    void aLongNumberThatFailsLuhnIsNotACard() {
        assertThat(Redactor.redact("число 1234567890123456 в тексте")).isEqualTo("число 1234567890123456 в тексте");
    }

    @Test
    void digitGroupsInsideAnAddressAreNotTelephoneNumbers() {
        for (String url : new String[] {"https://example.org/a/123-456-7890", "https://example.org/?id=123 456 7890", "https://example.org/x_89161234567"}) {
            assertThat(Redactor.redact("см. " + url + " тут")).as(url).isEqualTo("см. " + url + " тут");
        }
        assertThat(Redactor.redact("звоните 123-456-7890")).isEqualTo("звоните [phone]");
    }

    @Test
    void telephoneNumbersInCommonFormsAreRedacted() {
        for (String phone : new String[] {"+7 916 123-45-67", "+1 (415) 555-2671", "8 (916) 123 45 67", "(495) 123-45-67", "89161234567",
                "916 123 45 67", "415-555-2671", "+44 20 7946 0958"}) {
            assertThat(Redactor.redact("звоните " + phone + " вечером")).as(phone).isEqualTo("звоните [phone] вечером");
        }
    }

    @Test
    void ordinaryNumbersDatesAndDecimalsSurvive() {
        for (String text : new String[] {"в 2024 году", "дата 2024-10-02", "число пи 3.14159265358979", "миллиард 1000000000",
                "версия 1.2.3", "время 12:30:45", "глава 7", "страница 10-15", "ISBN-неважно 12345"}) {
            assertThat(Redactor.redact(text)).as(text).isEqualTo(text);
        }
        assertThat(Redactor.redact(null)).isNull();
        assertThat(Redactor.redact("")).isEmpty();
    }
}

package app.mnema.learning.billing;

import java.math.BigDecimal;

/** Kopecks written as the service expects them: rubles with two decimals. */
final class FakeMyTaxMoney {
    private FakeMyTaxMoney() { }

    static String rubles(String kopecks) {
        return BigDecimal.valueOf(Long.parseLong(kopecks), 2).toPlainString();
    }
}

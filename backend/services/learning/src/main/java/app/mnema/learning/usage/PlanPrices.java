package app.mnema.learning.usage;

import org.springframework.stereotype.Component;

/** The monthly price of a plan in whole rubles, from {@code contracts/usage/allowances-v1.json}: the one number billing charges (before a discount). */
@Component
public final class PlanPrices {
    private final AllowanceCatalog catalog;

    PlanPrices(AllowanceCatalog catalog) {
        this.catalog = catalog;
    }

    public int rubPerMonth(Plan plan) {
        return catalog.facts(plan).priceRubPerMonth();
    }
}

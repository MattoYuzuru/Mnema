package app.mnema.learning.onboarding;

import java.util.UUID;

/**
 * The {@code sampleDeck} member of the onboarding view ({@code contracts/onboarding/journey.json}).
 *
 * @param available       new sample decks can be made: the switch is on and the configured template is ready (live, marked, published at LINK)
 * @param deckId          the account's live sample deck, or null
 * @param updateAvailable that deck has an update of the template waiting (the ordinary «Есть обновление» of the copy)
 * @param freeChecksLeft  the AI checks still free for the account in its sample deck, per account and not per copy
 */
record SampleDeckState(boolean available, UUID deckId, boolean updateAvailable, int freeChecksLeft) {
    /** No sample deck and none to make; {@code freeChecksLeft} is what the configuration gives an account. */
    static SampleDeckState unavailable(int freeChecksLeft) {
        return new SampleDeckState(false, null, false, freeChecksLeft);
    }
}

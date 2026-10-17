package app.mnema.learning.library;

/**
 * What a deck is for ({@code deck.purpose}, migration V73). {@link #USER} is every ordinary deck. {@link #SAMPLE_TEMPLATE} is the editorial deck named by
 * {@code learning.onboarding.sample-deck.template-id} and {@link #SAMPLE} an account's copy of it («Моя первая колода»): neither can be published, shared or copied by a
 * user ({@link DeckPurposeGuard}), and neither feeds engagement, catalogue events or interests.
 */
public enum DeckPurpose {
    USER, SAMPLE_TEMPLATE, SAMPLE;

    /** Whether a user may publish, share or copy a deck of this purpose. */
    public boolean shareable() {
        return this == USER;
    }
}

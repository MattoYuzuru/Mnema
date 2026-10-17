package app.mnema.learning.onboarding;

import app.mnema.learning.library.DeckPurpose;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the onboarding sample deck: the account's live sample copy (served by the partial unique index {@code deck_one_live_sample}) and the one-statement mark of the
 * template. Nothing here writes a deck of an account; the copy is made by {@code ForkService.copyTemplate}.
 *
 * <p>TODO(account-deletion task; owner: Learning's account-purge epic #409): the sample deck is an ordinary deck of the account and goes with the other decks; the account's
 * entries of the usage ledger with operation {@code ONBOARDING_SAMPLE_CHECK} (partial index {@code usage_ledger_sample_check}) and its receipts of scope {@code onboarding}
 * (type {@code sample-deck.create}) go with the ledger and the receipts.
 */
@Repository
class SampleDeckRepository {
    /** What startup finds when it looks for the configured template. */
    record TemplateRow(DeckPurpose purpose, boolean deleted, String visibility, boolean published) { }

    private final JdbcClient jdbc;

    SampleDeckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes the sample-deck commands of one account until the end of the transaction, so parallel requests end with one live deck and the loser sees the winner's. */
    void lockAccount(UUID account) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "sample-deck:" + account).query().listOfRows();
    }

    /** The account's live sample deck. */
    Optional<UUID> liveSample(UUID account) {
        return jdbc.sql("SELECT deck_id FROM app_learning.deck WHERE owner_id = :account AND purpose = 'SAMPLE' AND deleted_at IS NULL")
                .param("account", account).query(UUID.class).optional();
    }

    /** Whether {@code deck} is the account's live sample deck. */
    boolean isLiveSample(UUID account, UUID deck) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.deck WHERE deck_id = :deck AND owner_id = :account AND purpose = 'SAMPLE' AND deleted_at IS NULL)")
                .param("deck", deck).param("account", account).query(Boolean.class).single();
    }

    /**
     * Marks the template: the one transition {@code USER} to {@code SAMPLE_TEMPLATE} of a live deck (the head guard of V73 allows exactly this change and no other change of
     * {@code purpose}). Idempotent; a deck already marked, deleted, missing or a sample copy is left as it is.
     *
     * @return whether this call marked it
     */
    boolean mark(UUID template) {
        return jdbc.sql("UPDATE app_learning.deck SET purpose = 'SAMPLE_TEMPLATE' WHERE deck_id = :deck AND purpose = 'USER' AND deleted_at IS NULL")
                .param("deck", template).update() == 1;
    }

    /** The configured template as it is now, for the startup log; empty when there is no such deck. */
    Optional<TemplateRow> template(UUID template) {
        return jdbc.sql("""
                SELECT d.purpose, d.deleted_at IS NOT NULL AS deleted, p.visibility, COALESCE(p.published_revision_id IS NOT NULL, FALSE) AS published
                  FROM app_learning.deck d LEFT JOIN app_learning.deck_publication p ON p.deck_id = d.deck_id WHERE d.deck_id = :deck
                """).param("deck", template)
                .query((row, ignored) -> new TemplateRow(DeckPurpose.valueOf(row.getString("purpose")), row.getBoolean("deleted"), row.getString("visibility"),
                        row.getBoolean("published"))).optional();
    }
}

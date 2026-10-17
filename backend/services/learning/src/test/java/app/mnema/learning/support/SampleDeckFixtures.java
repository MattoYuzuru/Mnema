package app.mnema.learning.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Makes an ordinary deck an onboarding sample deck without the copy (the free-check tests need a deck of the account that studies, not the template). */
public final class SampleDeckFixtures {
    private SampleDeckFixtures() { }

    /**
     * Sets {@code purpose = 'SAMPLE'} on a deck. The head guard of V73 (rightly) refuses every purpose change but the template mark, so it is switched off for this one statement
     * in this transaction (DDL is transactional: no other session ever sees the guard disabled).
     */
    public static void markSample(JdbcClient jdbc, PlatformTransactionManager transactions, UUID deck) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("ALTER TABLE app_learning.deck DISABLE TRIGGER deck_head_guard").update();
            jdbc.sql("UPDATE app_learning.deck SET purpose = 'SAMPLE' WHERE deck_id = :d").param("d", deck).update();
            jdbc.sql("ALTER TABLE app_learning.deck ENABLE TRIGGER deck_head_guard").update();
        });
    }
}

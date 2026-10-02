package app.mnema.learning.usage;

import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A stand-in for the admission endpoints of the generation module (createSession, editArtifact, retryArtifact): it
 * reserves or consumes in its own transaction and lets {@code USAGE_LIMIT_REACHED} propagate to the problem handler, the
 * way those endpoints will.
 */
// The profile is never active: tests mount the probe by hand, and a scanned test controller would add routes to every context.
@RestController
@Profile("usage-admission-probe")
final class AdmissionProbe {
    private final UsageLedger ledger;
    private final PlatformTransactionManager transactions;

    AdmissionProbe(UsageLedger ledger, PlatformTransactionManager transactions) {
        this.ledger = ledger;
        this.transactions = transactions;
    }

    @GetMapping("/decks/{deck}/generation-sessions")
    String reserve(@PathVariable String deck, @RequestHeader("X-Owner") UUID owner, @RequestHeader("X-Credits") int credits) {
        return new TransactionTemplate(transactions).execute(status ->
                ledger.reserve(owner, ReservationScope.SESSION, null, null, credits).reservationId().toString());
    }

    @GetMapping("/decks/{deck}/podcasts")
    String podcast(@PathVariable String deck, @RequestHeader("X-Owner") UUID owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            ledger.consume(owner, Bucket.PODCASTS, 1, "pod:" + UUID.randomUUID(), null);
            return "ok";
        });
    }
}

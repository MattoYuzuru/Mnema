package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.fork.ForkService;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.security.IdentityClaims.AccountSignals;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * «Моя первая колода» (OB-201, ADR-ONB-003): one live copy of the sample template per account.
 *
 * <p><b>Create.</b> Idempotent by command id through the global receipts (scope {@code onboarding}, type {@code sample-deck.create}; the receipt stores {@code {deckId, created}},
 * and the answer of a repeat is read again, so it is the account's current view). In one transaction under the account's advisory lock: if the account has a live {@code SAMPLE}
 * deck that is the answer ({@code created=false}, HTTP 200); otherwise {@link ForkService#copyTemplate} copies the published revision of the configured template with the ordinary
 * O(1) copy, inserted as {@code SAMPLE} (HTTP 201). The lock makes parallel requests of one account end with one live deck and the loser answering the winner's; the partial unique
 * index {@code deck_one_live_sample} is the net under it. A template that is not ready rolls the transaction back (no receipt), so a repeat after the operator fixed it works.
 * Materialization of the copy's heads stays the background job {@code deck.copy.materialize}; the answer names the deck at once.
 *
 * <p><b>State.</b> {@link #state} (a read in the caller's transaction) is the {@code sampleDeck} member of the onboarding view. The update flag is the copy's own {@code fork.updateAvailable} of the existing CD-5 read model.
 */
@Service
public class SampleDeckService {
    private static final String SCOPE = "onboarding";

    /** @param created this call made the copy (201); false: the account's live copy was returned (200) */
    record Created(UUID deckId, boolean created, boolean replayed) { }

    private final SampleDeckSettings settings;
    private final SampleDeckRepository repository;
    private final ForkService forks;
    private final DeckService decks;
    private final CommandReceiptService receipts;
    private final SampleChecks checks;
    private final MeterRegistry meters;

    SampleDeckService(SampleDeckSettings settings, SampleDeckRepository repository, ForkService forks, DeckService decks, CommandReceiptService receipts,
                      SampleChecks checks, MeterRegistry meters) {
        this.settings = settings;
        this.repository = repository;
        this.forks = forks;
        this.decks = decks;
        this.receipts = receipts;
        this.checks = checks;
        this.meters = meters;
    }

    /** @throws SampleDeckUnavailableException the switch is off or no template is configured */
    void requireConfigured() {
        if (!settings.configured()) throw new SampleDeckUnavailableException();
    }

    /** The free checks an account is given; what the view of a switched-off tour reports. */
    int freeAiChecks() {
        return settings.freeAiChecks();
    }

    /**
     * Makes the account's sample deck, or returns the live one. Runs in the caller's transaction ({@link OnboardingService#sampleDeck}); the receipt joins it.
     *
     * @throws SampleDeckUnavailableException the switch is off, or the template is not ready
     */
    Created create(UUID account, OnboardingCommands.SampleDeck command, AccountSignals signals) {
        UuidPolicy.requireEntityId(account, "account");
        requireConfigured();
        boolean[] ran = new boolean[1];
        JsonNode stored = receipts.execute(new CommandIdentity(command.commandId(), account, SCOPE, "sample-deck.create"), command.envelope(), () -> {
            ran[0] = true;
            return make(account, command, signals);
        });
        UUID deck = UUID.fromString(stored.path("deckId").stringValue(null));
        return new Created(deck, stored.path("created").booleanValue(false), !ran[0]);
    }

    private JsonNode make(UUID account, OnboardingCommands.SampleDeck command, AccountSignals signals) {
        repository.lockAccount(account);
        Optional<UUID> live = repository.liveSample(account);
        if (live.isPresent()) {
            meters.counter("mnema_onboarding_sample_deck_total", "outcome", "existing").increment();
            return receipt(live.get(), false);
        }
        UUID template = settings.templateId().orElseThrow(SampleDeckUnavailableException::new);
        ForkService.TemplateCopy copy = forks.copyTemplate(account, template, command.commandId(), signals).orElseThrow(SampleDeckUnavailableException::new);
        meters.counter("mnema_onboarding_sample_deck_total", "outcome", copy.created() ? "created" : "existing").increment();
        return receipt(copy.deckId(), copy.created());
    }

    private static JsonNode receipt(UUID deck, boolean created) {
        return JsonNodeFactory.instance.objectNode().put("deckId", deck.toString()).put("created", created);
    }

    /** The {@code sampleDeck} member of the account's onboarding view. */
    SampleDeckState state(UUID account) {
        int left = checks.left(account);
        Optional<UUID> deck = repository.liveSample(account);
        boolean available = settings.configured() && forks.templateReady(settings.templateId().orElseThrow());
        return new SampleDeckState(available, deck.orElse(null), deck.isPresent() && updateAvailable(account, deck.get()), left);
    }

    /** The copy's own «Есть обновление» (the CD-5 read model of the owner's deck read); a copy deleted between the two reads has none. */
    private boolean updateAvailable(UUID account, UUID deck) {
        try {
            return decks.read(account, deck).path("fork").path("updateAvailable").booleanValue(false);
        } catch (ResourceNotFoundException gone) {
            return false;
        }
    }
}

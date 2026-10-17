package app.mnema.learning.onboarding;

import app.mnema.learning.library.DeckPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Marks the configured sample template (OB-201). There is no API for it: the editorial account creates and publishes its deck through the ordinary authoring API, the
 * operator puts the id into {@code learning.onboarding.sample-deck.template-id}, and when the application is ready (every runtime role; the statement is idempotent and the roles
 * race harmlessly) one statement sets {@code purpose = SAMPLE_TEMPLATE} on that deck if it exists, is not deleted and is still {@code USER}. From then on it cannot be shared or
 * copied by a user and its owner can publish only at LINK. A template that is missing, deleted, not published or not at LINK is logged (the deck id is configuration, not personal
 * data) and the sample deck stays unavailable; a deck shared at PUBLIC or INVITE level is not marked (an ERROR names the template only), and the number of accounts that already hold an
 * ordinary copy of it is logged as a count; a failure of the database does not stop startup. A deck already marked is left alone, and so is one that is a sample copy.
 */
@Component
final class SampleTemplateMarker {
    private static final Logger LOG = LoggerFactory.getLogger(SampleTemplateMarker.class);

    private final SampleDeckSettings settings;
    private final SampleDeckRepository repository;

    SampleTemplateMarker(SampleDeckSettings settings, SampleDeckRepository repository) {
        this.settings = settings;
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        try {
            mark();
        } catch (RuntimeException failure) {
            LOG.warn("sample_template_mark_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    /** Marks the template if one is configured and reports what it found. */
    void mark() {
        Optional<UUID> configured = settings.templateId();
        if (configured.isEmpty()) return;
        UUID template = configured.orElseThrow();
        if (repository.mark(template)) LOG.info("sample_template_marked template_id={}", template);
        long copies = repository.liveOrdinaryCopies(template);
        // Accounts that copied the deck by the public route before it was marked keep an ordinary deck, which is not their sample deck (POST answers 409 until they delete it).
        if (copies > 0) LOG.warn("sample_template_user_copies template_id={} count={}", template, copies);
        Optional<SampleDeckRepository.TemplateRow> row = repository.template(template);
        if (row.isEmpty()) {
            LOG.warn("sample_template_missing template_id={}", template);
        } else if (row.get().deleted()) {
            LOG.warn("sample_template_deleted template_id={}", template);
        } else if (row.get().purpose() == DeckPurpose.SAMPLE) {
            LOG.error("sample_template_is_a_sample_copy template_id={}", template);
        } else if (row.get().purpose() == DeckPurpose.USER && ("PUBLIC".equals(row.get().visibility()) || "INVITE".equals(row.get().visibility()))) {
            // not marked: a deck shared at these levels is open to others (or in the catalogue); its owner lowers it to LINK through the ordinary command, the next start marks it
            LOG.error("sample_template_shared_level template_id={} visibility={}", template, row.get().visibility());
        } else if (!row.get().published() || !"LINK".equals(row.get().visibility())) {
            LOG.warn("sample_template_not_ready template_id={} published={} visibility={}", template, row.get().published(), row.get().visibility());
        }
    }
}

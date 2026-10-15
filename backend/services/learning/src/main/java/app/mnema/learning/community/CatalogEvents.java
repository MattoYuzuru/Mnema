package app.mnema.learning.community;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.api.ResourceNotFoundException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Recs/1 telemetry in the successful effect transaction, never from a client-supplied account or private text. */
@Service
public class CatalogEvents {
    record Source(UUID deck, UUID revision, String topic, String language) { }

    private final JdbcClient jdbc;
    private final RecommendationSettings settings;
    private final CommandReceiptService receipts;
    private final InterestService interests;

    CatalogEvents(JdbcClient jdbc, RecommendationSettings settings, CommandReceiptService receipts, InterestService interests) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.receipts = receipts;
        this.interests = interests;
    }

    void requireEnabled() {
        if (!settings.eventsEnabled) throw new ResourceNotFoundException();
    }

    private Optional<Source> source(String predicate, UUID account, Object value) {
        return jdbc.sql("SELECT p.deck_id,p.published_revision_id,p.topic_id,p.target_language FROM app_learning.public_deck p WHERE p.listed AND p.owner_id<>:a AND " + predicate)
                .param("a", account).param("v", value).query((r, ignored) -> new Source(r.getObject("deck_id", UUID.class), r.getObject("published_revision_id", UUID.class),
                        r.getString("topic_id"), r.getString("target_language"))).optional();
    }

    private Optional<Source> copySource(UUID account, UUID copy) {
        return source("p.deck_id=(SELECT upstream_deck_id FROM app_learning.deck_fork WHERE deck_id=:v AND owner_id=:a AND released_at IS NULL)", account, copy);
    }

    @Transactional(timeout = 10)
    void event(UUID account, RecommendationCommands.Event command) {
        requireEnabled();
        receipts.execute(new CommandIdentity(command.commandId(), account, "community.events", "event.record"), command.envelope(), () -> {
            interests.lockSignal(account);
            Source source = source("p.public_code=:v", account, command.code()).orElseThrow(ResourceNotFoundException::new);
            insert(account, source, command.kind(), command.surface(), command.position(), command.rankVersion());
            return JsonNodeFactory.instance.objectNode();
        });
    }

    @Transactional(timeout = 10)
    void exposure(UUID account, RecommendationCommands.Exposure command) {
        requireEnabled();
        receipts.execute(new CommandIdentity(command.commandId(), account, "community.events", "exposure.record"), command.envelope(), () -> {
            // Exposures are anonymous aggregates: an owned public deck can be displayed by ordinary catalogue reads.
            List<UUID> decks = jdbc.sql("SELECT deck_id FROM app_learning.public_deck WHERE listed AND public_code IN (:codes)").param("codes", command.codes()).query(UUID.class).list();
            if (decks.size() != command.codes().size()) throw new InvalidRequestException();
            for (UUID deck : decks) {
                jdbc.sql("""
                INSERT INTO app_learning.deck_exposure_day(day,deck_id,surface,rank_version,impressions)
                VALUES ((statement_timestamp() AT TIME ZONE 'UTC')::date,:d,:s,:r,1)
                ON CONFLICT (day,deck_id,surface,rank_version) DO UPDATE
                SET impressions=app_learning.deck_exposure_day.impressions+1
                """)
                    .param("d", deck).param("s", command.surface()).param("r", command.rankVersion()).update();
            }
            return JsonNodeFactory.instance.objectNode();
        });
    }

    private void insert(UUID account, Source source, String kind, String surface, Integer position, String version) {
        jdbc.sql("""
                INSERT INTO app_learning.catalog_event(account_id,deck_id,published_revision_id,topic_id,target_language,kind,surface,position,rank_version)
                VALUES (:a,:d,:r,:t,:l,:k,:s,:p,:v)
                """).param("a",
                account).param("d", source.deck()).param("r", source.revision()).param("t", source.topic()).param("l", source.language())
                .param("k", kind).param("s", surface).param("p", position, java.sql.Types.INTEGER).param("v", version).update();
        jdbc.sql("UPDATE app_learning.community_interest_profile SET last_active_at=statement_timestamp() WHERE account_id=:a").param("a", account).update();
    }

    private String attribution(UUID account, Source source, boolean fork) {
        String condition = fork ? "kind IN ('DETAIL_VIEW','SEARCH_CLICK') AND occurred_at>=statement_timestamp()-interval '30 minutes'" : "kind='FORK'";
        return jdbc.sql("SELECT rank_version FROM app_learning.catalog_event WHERE account_id=:a AND deck_id=:d AND occurred_at<=statement_timestamp() AND " + condition
                + " ORDER BY occurred_at DESC,event_id DESC LIMIT 1")
                .param("a", account).param("d", source.deck()).query(String.class).optional().orElse("catalog-v1");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void forked(UUID account, UUID deck) {
        if (!settings.eventsEnabled && !settings.forYouEnabled) return;
        interests.lockSignal(account);
        source("p.deck_id=:v", account, deck).ifPresent(s -> {
            if (settings.eventsEnabled) insert(account, s, "FORK", "copy", null, attribution(account, s, true));
            interests.changed(account);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void studyStarted(UUID account, UUID copy) {
        if (!settings.eventsEnabled) return;
        interests.lockSignal(account);
        copySource(account, copy).ifPresent(s -> insert(account, s, "STUDY_START", "study", null, attribution(account, s, false)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void dropped(UUID account, UUID copy) {
        if (!settings.eventsEnabled && !settings.forYouEnabled) return;
        interests.lockSignal(account);
        source("p.deck_id=(SELECT upstream_deck_id FROM app_learning.deck_fork WHERE deck_id=:v AND owner_id=:a)", account, copy).ifPresent(s -> {
            if (settings.eventsEnabled) insert(account, s, "DROP", "decks", null, attribution(account, s, false));
            interests.changed(account);
        });
    }
}

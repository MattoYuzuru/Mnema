package app.mnema.learning.speech;

import app.mnema.learning.ai.Transcription;
import app.mnema.learning.capability.LearningCapabilities;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The voice consent of an account ({@code contracts/speech}): voice is personal data, so a microphone input needs a consent for the region it is
 * processed in: {@code RU} (a self-hosted container in Russia) or {@code ABROAD} (a foreign provider through the egress gateway, the audio de-identified).
 * The version of the text is {@code learning.speech.consent-version}. A consent for {@code ABROAD} covers {@code RU} (the audio then stays at home), a
 * consent for {@code RU} does not cover {@code ABROAD}, so a move of a route to a foreign provider asks again.
 *
 * <p>{@code GET} without a language states the widest region any route can use (what the learner must accept to dictate in every language); an input
 * states the region of its own language.
 */
@Service
class SpeechConsents {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Transcription transcription;
    private final SpeechInputSettings settings;
    private final LearningCapabilities capabilities;

    SpeechConsents(JdbcClient jdbc, Transcription transcription, SpeechInputSettings settings, LearningCapabilities capabilities) {
        this.jdbc = jdbc;
        this.transcription = transcription;
        this.settings = settings;
        this.capabilities = capabilities;
    }

    private record Accepted(String version, Transcription.Region processing, Instant acceptedAt) { }

    /** The region the disclosure names for {@code lang}: its own route's, or (no language) the widest of the routes; the capability must be there. */
    Transcription.Region required(String lang) {
        var status = capabilities.speechToText();
        // a provider that is down for a while does not hide the disclosure; a disabled or unconfigured capability does
        if (!status.available() && status.reason() != LearningCapabilities.Reason.TEMPORARILY_UNAVAILABLE) capabilities.requireSpeechToText();
        if (lang != null) return transcription.region(lang).orElseGet(() -> widest());
        return widest();
    }

    private Transcription.Region widest() {
        Optional<Transcription.Region> general = transcription.region(null);
        Optional<Transcription.Region> russian = transcription.region("ru");
        if (general.orElse(null) == Transcription.Region.ABROAD || russian.orElse(null) == Transcription.Region.ABROAD) return Transcription.Region.ABROAD;
        return Transcription.Region.RU;
    }

    ObjectNode view(UUID owner) {
        Transcription.Region processing = required(null);
        ObjectNode body = JSON.createObjectNode();
        body.putObject("required").put("version", settings.consentVersion()).put("processing", processing.name());
        Optional<Accepted> accepted = find(owner);
        if (accepted.isEmpty()) {
            body.putNull("accepted");
        } else {
            body.putObject("accepted").put("version", accepted.get().version()).put("processing", accepted.get().processing().name())
                    .put("acceptedAt", accepted.get().acceptedAt().toString());
        }
        return body;
    }

    /**
     * Records the consent of {@code version} for {@code processing}; idempotent (the same consent again keeps its time).
     *
     * @throws SpeechConsentOutdatedException the version or the region is not the current one
     */
    ObjectNode accept(UUID owner, String version, Transcription.Region processing) {
        Transcription.Region required = required(null);
        if (!settings.consentVersion().equals(version) || required != processing) throw new SpeechConsentOutdatedException(settings.consentVersion(), required);
        jdbc.sql("INSERT INTO app_learning.speech_consent(owner_id,version,processing,accepted_at) VALUES (:owner,:version,:processing,CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (owner_id) DO UPDATE SET version=EXCLUDED.version, processing=EXCLUDED.processing, accepted_at=EXCLUDED.accepted_at "
                        + "WHERE (app_learning.speech_consent.version, app_learning.speech_consent.processing) IS DISTINCT FROM (EXCLUDED.version, EXCLUDED.processing)")
                .param("owner", owner).param("version", version).param("processing", processing.name()).update();
        return view(owner);
    }

    /** Withdraws the consent; nothing to withdraw is not an error. */
    void withdraw(UUID owner) {
        jdbc.sql("DELETE FROM app_learning.speech_consent WHERE owner_id=:owner").param("owner", owner).update();
    }

    /**
     * Whether the account has consented for {@code needed}.
     *
     * @throws SpeechConsentRequiredException no consent, an old version, or one for a narrower region
     */
    void require(UUID owner, Transcription.Region needed) {
        Optional<Accepted> accepted = find(owner);
        boolean covers = accepted.isPresent() && settings.consentVersion().equals(accepted.get().version())
                && (accepted.get().processing() == Transcription.Region.ABROAD || accepted.get().processing() == needed);
        if (!covers) throw new SpeechConsentRequiredException(settings.consentVersion(), needed);
    }

    private Optional<Accepted> find(UUID owner) {
        return jdbc.sql("SELECT version,processing,accepted_at FROM app_learning.speech_consent WHERE owner_id=:owner").param("owner", owner)
                .query((row, ignored) -> new Accepted(row.getString("version"), Transcription.Region.valueOf(row.getString("processing")),
                        row.getTimestamp("accepted_at").toInstant())).optional();
    }
}

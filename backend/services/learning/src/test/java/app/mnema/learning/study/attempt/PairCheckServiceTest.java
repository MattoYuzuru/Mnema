package app.mnema.learning.study.attempt;

import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PairCheckServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final UUID actor = UUID.randomUUID(), deck = UUID.randomUUID(), session = UUID.randomUUID();
    private final UUID presentationId = UUID.randomUUID(), cue = UUID.randomUUID(), option = UUID.randomUUID();
    private final PairCheckCommand command = new PairCheckCommand(presentationId, "1234567890123456", cue, option);
    private final AttemptRepository repository = mock(AttemptRepository.class);
    private final MediaCatalog media = mock(MediaCatalog.class);
    private final AttemptService service = new AttemptService(repository, new CanonicalJsonHasher(), media);

    @Test
    void unauthorizedAndUnknownPresentationCannotProbePairs() {
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(ResourceNotFoundException.class);
        when(repository.ownsDeck(actor, deck)).thenReturn(true);
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).insertPairInteraction(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void nonceExpiryTypeMediaAndUnissuedTargetsFailBeforeInteractionWrites() {
        setup("AUDIO_TEXT_MATCH", "other-nonce");
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(ResourceNotFoundException.class);
        setup("AUDIO_TEXT_MATCH", command.nonce());
        when(repository.now()).thenReturn(Instant.parse("2026-09-20T11:00:00Z"));
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(PresentationExpiredException.class);
        setup("TYPED", command.nonce());
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(InvalidRequestException.class);
        setup("AUDIO_TEXT_MATCH", command.nonce());
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(InvalidRequestException.class);
        when(media.exerciseReady(any(), any(), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.checkPair(actor, deck, session,
                new PairCheckCommand(presentationId, command.nonce(), UUID.randomUUID(), option)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.checkPair(actor, deck, session,
                new PairCheckCommand(presentationId, command.nonce(), cue, UUID.randomUUID())))
                .isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).insertPairInteraction(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void recordedPairAcknowledgementSurvivesExpiryWithoutNewWrite() {
        setup("AUDIO_TEXT_MATCH", command.nonce());
        when(repository.now()).thenReturn(Instant.parse("2026-09-20T11:00:00Z"));
        when(repository.pairInteraction(actor, session, command)).thenReturn(Optional.of(false));
        assertThat(service.checkPair(actor, deck, session, command)).isFalse();
        verify(repository, never()).insertPairInteraction(any(), any(), any(), anyBoolean(), any());
    }

    private void setup(String type, String nonce) {
        var answer = JSON.createObjectNode().put("schemaVersion", 2);
        answer.putArray("pairs").addObject().put("cueId", cue.toString()).put("optionId", option.toString());
        var presentation = new AttemptRepository.Presentation(actor, session, presentationId, deck,
                "SCHEDULED", "ACTIVE", nonce, UUID.randomUUID(), UUID.randomUUID(), type,
                UUID.randomUUID(), UUID.randomUUID(), 0, JSON.createArrayNode(),
                JSON.createObjectNode().put("id", "deterministic-audio-match").put("version", "1"),
                answer, false, UUID.randomUUID(), "mnema-baseline", "1", "hash",
                Instant.parse("2026-09-20T10:30:00Z"));
        when(repository.ownsDeck(actor, deck)).thenReturn(true);
        when(repository.presentationForUpdate(actor, deck, session, presentationId)).thenReturn(Optional.of(presentation));
        when(repository.now()).thenReturn(Instant.parse("2026-09-20T10:00:00Z"));
    }
}

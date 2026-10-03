package app.mnema.learning.study.attempt;

import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
    private final UUID presentationId = UUID.randomUUID();
    private final UUID left = UUID.randomUUID(), right = UUID.randomUUID();
    private final UUID otherLeft = UUID.randomUUID(), otherRight = UUID.randomUUID();
    private final PairCheckCommand command = new PairCheckCommand(presentationId, "1234567890123456", left, right);
    private final AttemptRepository repository = mock(AttemptRepository.class);
    private final MediaCatalog media = mock(MediaCatalog.class);
    private final AttemptService service = new AttemptService(repository, new CanonicalJsonHasher(), media,
            mock(AttemptConclusion.class), mock(AssessmentService.class));

    @Test
    void unauthorizedAndUnknownPresentationCannotProbePairs() {
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(ResourceNotFoundException.class);
        when(repository.ownsDeck(actor, deck)).thenReturn(true);
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).insertPairInteraction(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void nonceExpiryTypeMediaAndUnissuedTargetsFailBeforeInteractionWrites() {
        setup("MATCH", "other-nonce");
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(ResourceNotFoundException.class);
        setup("MATCH", command.nonce());
        when(repository.now()).thenReturn(Instant.parse("2026-09-20T11:00:00Z"));
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(PresentationExpiredException.class);
        setup("MATCH", command.nonce());
        when(repository.terminal(actor, session, presentationId)).thenReturn(Optional.of(mock(AttemptRepository.Receipt.class)));
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(IdempotencyConflictException.class);
        setup("CHOICE", command.nonce());
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(InvalidRequestException.class);
        setup("MATCH", command.nonce());
        assertThatThrownBy(() -> service.checkPair(actor, deck, session, command)).isInstanceOf(InvalidRequestException.class);
        when(media.exerciseMediaReady(any(), any(), any(), any())).thenReturn(true);
        // neither id was issued to this learner
        assertThatThrownBy(() -> service.checkPair(actor, deck, session,
                new PairCheckCommand(presentationId, command.nonce(), UUID.randomUUID(), right)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.checkPair(actor, deck, session,
                new PairCheckCommand(presentationId, command.nonce(), left, UUID.randomUUID())))
                .isInstanceOf(InvalidRequestException.class);
        verify(repository, never()).insertPairInteraction(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void theAnswerKeyDecidesCorrectnessAndEachDistinctPairIsRecorded() {
        setup("MATCH", command.nonce());
        when(media.exerciseMediaReady(any(), any(), any(), any())).thenReturn(true);
        assertThat(service.checkPair(actor, deck, session, command)).isTrue();
        PairCheckCommand wrong = new PairCheckCommand(presentationId, command.nonce(), left, otherRight);
        assertThat(service.checkPair(actor, deck, session, wrong)).isFalse();
        verify(repository).insertPairInteraction(any(), any(), any(), org.mockito.ArgumentMatchers.eq(true), any());
        verify(repository).insertPairInteraction(any(), any(), any(), org.mockito.ArgumentMatchers.eq(false), any());
    }

    @Test
    void recordedPairAcknowledgementSurvivesExpiryWithoutNewWrite() {
        setup("MATCH", command.nonce());
        when(repository.now()).thenReturn(Instant.parse("2026-09-20T11:00:00Z"));
        when(repository.pairInteraction(actor, session, command)).thenReturn(Optional.of(false));
        assertThat(service.checkPair(actor, deck, session, command)).isFalse();
        verify(repository, never()).insertPairInteraction(any(), any(), any(), anyBoolean(), any());
    }

    private void setup(String type, String nonce) {
        org.mockito.Mockito.reset(repository, media);
        var key = JSON.createObjectNode().put("kind", "MATCH");
        key.putArray("pairs").addObject().put("leftId", left.toString()).put("rightId", right.toString());
        key.withArray("pairs").addObject().put("leftId", otherLeft.toString()).put("rightId", otherRight.toString());
        var content = JSON.createObjectNode();
        content.putArray("left").addObject().put("itemId", left.toString());
        content.withArray("left").addObject().put("itemId", otherLeft.toString());
        content.putArray("right").addObject().put("itemId", right.toString());
        content.withArray("right").addObject().put("itemId", otherRight.toString());
        var presentation = new AttemptRepository.Presentation(actor, session, presentationId, deck,
                "SCHEDULED", "ACTIVE", nonce, UUID.randomUUID(), UUID.randomUUID(), type,
                UUID.randomUUID(), UUID.randomUUID(), 0, content, JSON.createObjectNode(),
                JSON.createObjectNode().put("id", "deterministic-match").put("version", "1"),
                key, false, List.of(), UUID.randomUUID(), "mnema-baseline", "1", "hash",
                Instant.parse("2026-09-20T10:30:00Z"));
        when(repository.ownsDeck(actor, deck)).thenReturn(true);
        when(repository.presentationForUpdate(actor, deck, session, presentationId)).thenReturn(Optional.of(presentation));
        when(repository.now()).thenReturn(Instant.parse("2026-09-20T10:00:00Z"));
    }
}

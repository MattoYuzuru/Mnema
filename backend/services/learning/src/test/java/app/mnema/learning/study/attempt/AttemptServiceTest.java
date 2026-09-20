package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttemptServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void expiredPresentationStopsBeforeEvaluationOrAnyWrite() {
        AttemptRepository repository = mock(AttemptRepository.class);
        AttemptService service = new AttemptService(repository, new CanonicalJsonHasher());
        UUID actor = UUID.randomUUID(), deck = UUID.randomUUID(), session = UUID.randomUUID();
        AttemptCommand command = new AttemptCommand(UUID.randomUUID(), UUID.randomUUID(), "1234567890123456",
                new AttemptCommand.TextResponse("answer"), List.of(), null, 10, JSON.createObjectNode());
        var answer = JSON.createObjectNode();
        answer.putArray("accepted").add("answer");
        var presentation = new AttemptRepository.Presentation(actor, session, command.presentationId(), deck,
                "SCHEDULED", "ACTIVE", command.nonce(), "TYPED", UUID.randomUUID(), UUID.randomUUID(), 0,
                JSON.createObjectNode().put("id", "deterministic-text").put("version", "1"),
                answer, UUID.randomUUID(),
                "mnema-baseline", "1", "hash", Instant.parse("2026-09-20T09:00:00Z"));
        when(repository.ownsDeck(actor, deck)).thenReturn(true);
        when(repository.receipt(command.attemptId())).thenReturn(Optional.empty());
        when(repository.presentationForUpdate(actor, deck, session, command.presentationId()))
                .thenReturn(Optional.of(presentation));
        when(repository.now()).thenReturn(Instant.parse("2026-09-20T09:00:01Z"));

        assertThatThrownBy(() -> service.submit(actor, deck, session, command))
                .isInstanceOf(PresentationExpiredException.class);
        verify(repository, never()).insertRaw(any(), any(), any());
        verify(repository, never()).insertEvidence(any(), any(), any(), any());
    }
}

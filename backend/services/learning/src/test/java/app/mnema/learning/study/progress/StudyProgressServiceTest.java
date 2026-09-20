package app.mnema.learning.study.progress;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudyProgressServiceTest {
    private final StudyProgressRepository repository = mock(StudyProgressRepository.class);
    private final StudyProgressService service = new StudyProgressService(repository);
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();

    @Test
    void cursorIsOpaqueVersionedAndRoundTripsTheLastMember() {
        Instant now = Instant.parse("2026-09-20T10:00:00Z");
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
        var firstRow = row(first);
        var secondRow = row(second);
        when(repository.ownsDeck(actor, deck)).thenReturn(true);
        when(repository.now()).thenReturn(now);
        when(repository.page(actor, deck, null, 2, now)).thenReturn(List.of(firstRow, secondRow));

        var page = service.read(actor, deck, 1, null);
        String cursor = page.path("nextCursor").textValue();
        assertThat(cursor).doesNotContain(first.toString());
        when(repository.page(actor, deck, first, 2, now)).thenReturn(List.of(secondRow));
        assertThat(service.read(actor, deck, 1, cursor).path("items").get(0).path("memberKey").textValue())
                .isEqualTo(second.toString());
        verify(repository).page(actor, deck, first, 2, now);
    }

    @Test
    void invalidLimitsAndCursorsFailClosed() {
        when(repository.ownsDeck(actor, deck)).thenReturn(true);
        assertThatThrownBy(() -> service.read(actor, deck, 0, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.read(actor, deck, 20, "not-a-cursor"))
                .isInstanceOf(InvalidRequestException.class);
    }

    private StudyProgressRepository.Material row(UUID member) {
        return new StudyProgressRepository.Material(member, UUID.randomUUID(), 1, 0, 0,
                false, false, null, null);
    }
}

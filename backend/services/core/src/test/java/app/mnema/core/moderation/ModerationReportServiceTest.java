package app.mnema.core.moderation;

import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.security.ContentAdminAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModerationReportServiceTest {

    @Mock
    ModerationReportRepository moderationReportRepository;

    @Mock
    PublicDeckRepository publicDeckRepository;

    @Mock
    PublicCardRepository publicCardRepository;

    @Mock
    CardTemplateRepository cardTemplateRepository;

    @Mock
    ContentAdminAccessService contentAdminAccessService;

    @InjectMocks
    ModerationReportService moderationReportService;

    @Test
    void createReport_persistsOpenDeckReport() {
        UUID reporterId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        when(publicDeckRepository.findLatestByDeckId(deckId))
                .thenReturn(Optional.of(publicDeck(deckId, authorId, "Shared English")));
        when(moderationReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
                reporterId,
                ModerationReportTargetType.DECK,
                deckId,
                ModerationReportStatus.OPEN
        )).thenReturn(false);
        when(moderationReportRepository.save(any(ModerationReportEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModerationReportService.ModerationReportDto result = moderationReportService.createReport(
                reporterId,
                "reporter",
                ModerationReportTargetType.DECK,
                deckId,
                ModerationReportReason.FACTUAL_ERROR,
                "Wrong translation on the first cards"
        );

        ArgumentCaptor<ModerationReportEntity> captor = ArgumentCaptor.forClass(ModerationReportEntity.class);
        verify(moderationReportRepository).save(captor.capture());
        assertThat(captor.getValue().getTargetTitle()).isEqualTo("Shared English");
        assertThat(captor.getValue().getReason()).isEqualTo(ModerationReportReason.FACTUAL_ERROR);
        assertThat(result.status()).isEqualTo(ModerationReportStatus.OPEN);
        assertThat(result.reporterUsername()).isEqualTo("reporter");
    }

    @Test
    void createReport_rejectsDuplicateOpenReport() {
        UUID reporterId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        when(cardTemplateRepository.findById(templateId))
                .thenReturn(Optional.of(new CardTemplateEntity(
                        templateId,
                        UUID.randomUUID(),
                        "Template",
                        "",
                        true,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        1
                )));
        when(moderationReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
                reporterId,
                ModerationReportTargetType.TEMPLATE,
                templateId,
                ModerationReportStatus.OPEN
        )).thenReturn(true);

        assertThatThrownBy(() -> moderationReportService.createReport(
                reporterId,
                "reporter",
                ModerationReportTargetType.TEMPLATE,
                templateId,
                ModerationReportReason.SPAM,
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void closeReport_marksReportClosedByAdmin() {
        UUID adminId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ModerationReportEntity report = new ModerationReportEntity(
                reportId,
                ModerationReportTargetType.CARD,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Card from Shared English",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "reporter",
                ModerationReportReason.BROKEN_FORMATTING,
                "Audio is missing"
        );

        when(moderationReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        ModerationReportService.ModerationReportDto result = moderationReportService.closeReport(
                adminId,
                "root-admin",
                reportId,
                "Removed broken media references"
        );

        verify(contentAdminAccessService).requireActiveAdmin(adminId);
        assertThat(result.status()).isEqualTo(ModerationReportStatus.CLOSED);
        assertThat(result.closedByUserId()).isEqualTo(adminId);
        assertThat(result.closedByUsername()).isEqualTo("root-admin");
        assertThat(result.resolutionNote()).isEqualTo("Removed broken media references");
    }

    @Test
    void createReport_buildsCardTargetTitleFromPreview() {
        UUID cardId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Bonjour");
        PublicDeckEntity deck = publicDeck(deckId, authorId, "French basics");
        PublicCardEntity card = new PublicCardEntity(
                deckId,
                3,
                deck,
                cardId,
                content,
                1,
                new String[0],
                Instant.now(),
                Instant.now(),
                true,
                "checksum"
        );

        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(cardId)).thenReturn(Optional.of(card));
        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 3)).thenReturn(Optional.of(deck));
        when(moderationReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(any(), any(), any(), any()))
                .thenReturn(false);
        when(moderationReportRepository.save(any(ModerationReportEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModerationReportService.ModerationReportDto result = moderationReportService.createReport(
                UUID.randomUUID(),
                "reporter",
                ModerationReportTargetType.CARD,
                cardId,
                ModerationReportReason.FACTUAL_ERROR,
                null
        );

        assertThat(result.targetTitle()).contains("French basics");
        assertThat(result.targetTitle()).contains("Bonjour");
    }

    private PublicDeckEntity publicDeck(UUID deckId, UUID authorId, String name) {
        return new PublicDeckEntity(
                deckId,
                1,
                authorId,
                name,
                "Description",
                null,
                UUID.randomUUID(),
                1,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null
        );
    }
}

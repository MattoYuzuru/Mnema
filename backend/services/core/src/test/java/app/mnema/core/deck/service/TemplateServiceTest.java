package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    CardTemplateRepository cardTemplateRepository;

    @Mock
    FieldTemplateRepository fieldTemplateRepository;

    @InjectMocks
    TemplateService templateService;

    @Test
    void getCardTemplatesByPage_delegatesToRepositoryAndJoinsFields() {
        UUID templateId = UUID.randomUUID();

        CardTemplateEntity template = new CardTemplateEntity(
                templateId,
                UUID.randomUUID(),
                "Name",
                "Desc",
                true,
                Instant.now(),
                null,
                null,
                null,
                null
        );

        Page<CardTemplateEntity> page = new PageImpl<>(
                List.of(template),
                PageRequest.of(0, 10),
                1
        );

        when(cardTemplateRepository.findByIsPublicTrueOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(page);

        FieldTemplateEntity field = new FieldTemplateEntity(
                UUID.randomUUID(),
                templateId,
                "front",
                "Front",
                null,
                true,
                true,
                0,
                null,
                null
        );

        when(fieldTemplateRepository.findByTemplateIdIn(eq(List.of(templateId))))
                .thenReturn(List.of(field));

        Page<CardTemplateDTO> result = templateService.getCardTemplatesByPage(1, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        CardTemplateDTO dto = result.getContent().getFirst();
        assertThat(dto.templateId()).isEqualTo(templateId);
        assertThat(dto.fields()).hasSize(1);
        assertThat(dto.fields().getFirst().name()).isEqualTo("front");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardTemplateRepository).findByIsPublicTrueOrderByCreatedAtDesc(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void getCardTemplatesByPage_throwsOnInvalidPageOrLimit() {
        assertThatThrownBy(() -> templateService.getCardTemplatesByPage(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> templateService.getCardTemplatesByPage(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createNewTemplate_savesTemplateAndFieldsAndReturnsDto() {
        UUID ownerId = UUID.randomUUID();

        CardTemplateDTO dto = new CardTemplateDTO(
                null,
                ownerId,
                "Name",
                "Desc",
                true,
                null,
                null,
                null,
                null,
                null,
                null
        );

        FieldTemplateDTO frontFieldDto = new FieldTemplateDTO(
                null,
                null,
                "front",
                "Front",
                null,
                true,
                true,
                0,
                null,
                null
        );

        FieldTemplateDTO backFieldDto = new FieldTemplateDTO(
                null,
                null,
                "back",
                "Back",
                null,
                true,
                false,
                0,
                null,
                null
        );

        CardTemplateEntity savedTemplate = new CardTemplateEntity(
                UUID.randomUUID(),
                ownerId,
                "Name",
                "Desc",
                true,
                Instant.now(),
                null,
                null,
                null,
                null
        );

        when(cardTemplateRepository.save(any(CardTemplateEntity.class)))
                .thenReturn(savedTemplate);

        FieldTemplateEntity savedFrontField = new FieldTemplateEntity(
                UUID.randomUUID(),
                savedTemplate.getTemplateId(),
                "front",
                "Front",
                null,
                true,
                true,
                0,
                null,
                null
        );

        FieldTemplateEntity savedBackField = new FieldTemplateEntity(
                UUID.randomUUID(),
                savedTemplate.getTemplateId(),
                "back",
                "Back",
                null,
                true,
                false,
                0,
                null,
                null
        );

        when(fieldTemplateRepository.saveAll(anyList()))
                .thenReturn(List.of(savedFrontField, savedBackField));

        CardTemplateDTO result = templateService.createNewTemplate(ownerId, dto, List.of(frontFieldDto, backFieldDto));

        assertThat(result.templateId()).isEqualTo(savedTemplate.getTemplateId());
        assertThat(result.ownerId()).isEqualTo(ownerId);
        assertThat(result.fields()).hasSize(2);
        assertThat(result.fields().getFirst().templateId()).isEqualTo(savedTemplate.getTemplateId());

        verify(cardTemplateRepository).save(any(CardTemplateEntity.class));
        verify(fieldTemplateRepository).saveAll(anyList());
    }
}

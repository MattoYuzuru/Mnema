package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TemplateService {
    private final CardTemplateRepository cardTemplateRepository;
    private final FieldTemplateRepository fieldTemplateRepository;

    public TemplateService(CardTemplateRepository cardTemplateRepository,
                           FieldTemplateRepository fieldTemplateRepository
    ) {
        this.cardTemplateRepository = cardTemplateRepository;
        this.fieldTemplateRepository = fieldTemplateRepository;
    }

    // GET /api/core/templates?page=1&limit=10 - получить все публичные шаблоны постранично
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<CardTemplateDTO> getCardTemplatesByPage(int page, int limit) {

        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        // 1. Грузим страницу публичных шаблонов
        Page<CardTemplateEntity> templatePage = cardTemplateRepository.findByIsPublicTrue(pageable);

        List<CardTemplateEntity> templates = templatePage.getContent();
        if (templates.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, templatePage.getTotalElements());
        }

        // 2. Собираем все templateId
        List<UUID> templateIds = templates.stream()
                .map(CardTemplateEntity::getTemplateId)
                .toList();

        // 3. Грузим поля одним запросом
        List<FieldTemplateEntity> fieldEntities = fieldTemplateRepository.findByTemplateIdIn(templateIds);

        // 4. Мапим по templateId
        Map<UUID, List<FieldTemplateDTO>> fieldsByTemplateId = fieldEntities.stream()
                .collect(Collectors.groupingBy(
                        FieldTemplateEntity::getTemplateId,
                        Collectors.mapping(this::toFieldTemplateDTO, Collectors.toList())
                ));

        // 5. Собираем DTO с полями
        List<CardTemplateDTO> dtoList = templates.stream()
                .map(entity -> toCardTemplateDTO(
                        entity,
                        fieldsByTemplateId.getOrDefault(entity.getTemplateId(), List.of())
                ))
                .toList();

        return new PageImpl<>(dtoList, pageable, templatePage.getTotalElements());
    }

    // POST /api/core/templates - создать шаблон (вместе с полями)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public CardTemplateDTO createNewTemplate(UUID currentUserId, CardTemplateDTO dto, List<FieldTemplateDTO> fieldsDto) {

        // 1. Создаем шаблон без полей
        CardTemplateEntity cardTemplate = new CardTemplateEntity(
                null,
                currentUserId,
                dto.name(),
                dto.description(),
                dto.isPublic(),
                Instant.now(),
                null,
                dto.layout(), // TODO
                dto.aiProfile(),
                dto.iconUrl()
        );

        // 2. Сейвим шаблон в БД
        CardTemplateEntity savedCardTemplate = cardTemplateRepository.save(cardTemplate);

        // 3. Сохраняем шаблоны полей в БД
        List<FieldTemplateEntity> cardFields = fieldsDto.stream()
                .map(this::toFieldTemplateEntity)
                .toList();

        List<FieldTemplateEntity> savedFieldTemplates = fieldTemplateRepository.saveAll(cardFields);

        // 4. Готовим шаблоны полей для возврата
        List<FieldTemplateDTO> fieldTemplateDTOList = savedFieldTemplates.stream()
                .map(this::toFieldTemplateDTO)
                .toList();

        return toCardTemplateDTO(savedCardTemplate, fieldTemplateDTOList);
    }

    // GET /api/core/templates/{templateId} - получить шаблон по айди
    public CardTemplateDTO getCardTemplateById(UUID templateId) {
        CardTemplateEntity entity = cardTemplateRepository.findById(templateId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));

        List<FieldTemplateDTO> fields = fieldTemplateRepository
                .findByTemplateIdOrderByOrderIndexAsc(templateId)
                .stream()
                .map(this::toFieldTemplateDTO)
                .toList();

        return toCardTemplateDTO(entity, fields);
    }

    // PATCH /api/core/templates/{templateId} - частично изменить шаблон (сам шаблон)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public CardTemplateDTO partiallyChangeCardTemplate(UUID currentId, UUID templateId) {
        return null;
    }

    // DELETE /api/core/templates/{templateId} - удалить шаблон (со всеми полями)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteTemplate(UUID currentUserId, UUID templateId) {

        // 1. Проверяем наличие шаблона
        CardTemplateEntity cardTemplate = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template with ID: " + templateId + " and owner ID: " + currentUserId + " not found.")
                );

        // 2. Удаляем все поля шаблона
        List<FieldTemplateEntity> cardFields = fieldTemplateRepository.findByTemplateId(templateId);
        if (!cardFields.isEmpty()) {
            fieldTemplateRepository.deleteAll(cardFields);
        }

        // 3. Удаляем шаблон
        cardTemplateRepository.delete(cardTemplate);
    }

    // POST /api/core/templates/{templateId}/fields - добавить поле в шаблон
    // PATCH /api/core/templates/{templateId}/fields/{fieldId} - частично изменить поле в шаблоне
    // DELETE /api/core/templates/{templateId}/fields/{fieldId} - удалить поле из шаблона

    private FieldTemplateEntity toFieldTemplateEntity(FieldTemplateDTO dto) {
        return new FieldTemplateEntity(
                dto.fieldId(),
                dto.templateId(),
                dto.name(),
                dto.label(),
                dto.fieldType(),
                dto.isRequired(),
                dto.isOnFront(),
                dto.orderIndex(),
                dto.defaultValue(),
                dto.helpText()
        );
    }

    private FieldTemplateDTO toFieldTemplateDTO(FieldTemplateEntity entity) {
        return new FieldTemplateDTO(
                entity.getFieldId(),
                entity.getTemplateId(),
                entity.getName(),
                entity.getLabel(),
                entity.getFieldType(),
                entity.isRequired(),
                entity.isOnFront(),
                entity.getOrderIndex(),
                entity.getDefaultValue(),
                entity.getHelpText()
        );
    }

    private CardTemplateDTO toCardTemplateDTO(CardTemplateEntity entity,
                                              List<FieldTemplateDTO> fields) {
        return new CardTemplateDTO(
                entity.getTemplateId(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getDescription(),
                entity.isPublic(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLayout(),
                entity.getAiProfile(),
                entity.getIconUrl(),
                fields
        );
    }
}

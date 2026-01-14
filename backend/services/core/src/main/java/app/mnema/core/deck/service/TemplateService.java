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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TemplateService {

    private final CardTemplateRepository cardTemplateRepository;
    private final FieldTemplateRepository fieldTemplateRepository;

    public TemplateService(CardTemplateRepository cardTemplateRepository,
                           FieldTemplateRepository fieldTemplateRepository) {
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

        Page<CardTemplateEntity> templatePage = cardTemplateRepository
                .findByIsPublicTrueOrderByCreatedAtDesc(pageable);

        return mapTemplatePage(templatePage);
    }

    // GET /api/core/templates?scope=mine - получить шаблоны пользователя
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<CardTemplateDTO> getUserTemplatesByPage(UUID currentUserId, int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<CardTemplateEntity> templatePage = cardTemplateRepository
                .findByOwnerIdOrderByCreatedAtDesc(currentUserId, pageable);

        return mapTemplatePage(templatePage);
    }

    // GET /api/core/templates?scope=all - публичные + свои шаблоны
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<CardTemplateDTO> getTemplatesForUserAndPublic(UUID currentUserId, int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<CardTemplateEntity> templatePage = cardTemplateRepository
                .findByIsPublicTrueOrOwnerIdOrderByCreatedAtDesc(currentUserId, pageable);

        return mapTemplatePage(templatePage);
    }

    // POST /api/core/templates - создать шаблон (вместе с полями)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public CardTemplateDTO createNewTemplate(UUID currentUserId,
                                             CardTemplateDTO dto,
                                             List<FieldTemplateDTO> fieldsDto) {

        // 0. Выбираем источник полей - либо отдельный аргумент, либо dto.fields()
        List<FieldTemplateDTO> fieldDtos;
        if (fieldsDto != null && !fieldsDto.isEmpty()) {
            fieldDtos = fieldsDto;
        } else if (dto.fields() != null) {
            fieldDtos = dto.fields();
        } else {
            fieldDtos = List.of();
        }

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

        // 2. Сохраняем шаблон в БД
        CardTemplateEntity savedCardTemplate = cardTemplateRepository.save(cardTemplate);

        // 3. Сохраняем поля шаблона в БД (проставляя правильный templateId)
        UUID templateId = savedCardTemplate.getTemplateId();

        List<FieldTemplateEntity> cardFields = fieldDtos.stream()
                .map(fieldDto -> {
                    FieldTemplateDTO normalized = new FieldTemplateDTO(
                            null,                  // fieldId генерится БД
                            templateId,                  // гарантированно тот же шаблон
                            fieldDto.name(),
                            fieldDto.label(),
                            fieldDto.fieldType(),
                            fieldDto.isRequired(),
                            fieldDto.isOnFront(),
                            fieldDto.orderIndex(),
                            fieldDto.defaultValue(),
                            fieldDto.helpText()
                    );
                    return toFieldTemplateEntity(normalized);
                })
                .toList();

        List<FieldTemplateEntity> savedFieldTemplates = cardFields.isEmpty()
                ? List.of()
                : fieldTemplateRepository.saveAll(cardFields);

        // 4. Подготовка полей для возврата
        List<FieldTemplateDTO> fieldTemplateDTOList = savedFieldTemplates.stream()
                .map(this::toFieldTemplateDTO)
                .toList();

        return toCardTemplateDTO(savedCardTemplate, fieldTemplateDTOList);
    }

    // GET /api/core/templates/{templateId} - получить шаблон по айди
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
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
    public CardTemplateDTO partiallyChangeCardTemplate(UUID currentUserId,
                                                       UUID templateId,
                                                       CardTemplateDTO dto) {

        CardTemplateEntity entity = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        // Обновляем только то, что есть в dto (String/JSON) + флаг публичности
        if (dto.name() != null) {
            entity.setName(dto.name());
        }
        if (dto.description() != null) {
            entity.setDescription(dto.description());
        }
        if (dto.layout() != null) {
            entity.setLayout(dto.layout());
        }
        if (dto.aiProfile() != null) {
            entity.setAiProfile(dto.aiProfile());
        }
        if (dto.iconUrl() != null) {
            entity.setIconUrl(dto.iconUrl());
        }

        entity.setPublic(dto.isPublic());

        entity.setUpdatedAt(Instant.now());

        CardTemplateEntity saved = cardTemplateRepository.save(entity);

        List<FieldTemplateDTO> fields = fieldTemplateRepository
                .findByTemplateIdOrderByOrderIndexAsc(templateId)
                .stream()
                .map(this::toFieldTemplateDTO)
                .toList();

        return toCardTemplateDTO(saved, fields);
    }

    // DELETE /api/core/templates/{templateId} - удалить шаблон (со всеми полями)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteTemplate(UUID currentUserId, UUID templateId) {

        // 1. Проверяем наличие шаблона и права
        CardTemplateEntity cardTemplate = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template with ID: " + templateId +
                                " and owner ID: " + currentUserId + " not found."
                ));

        // 2. Удаляем поля (в БД уже есть ON DELETE CASCADE, но так явнее)
        List<FieldTemplateEntity> cardFields = fieldTemplateRepository.findByTemplateId(templateId);
        if (!cardFields.isEmpty()) {
            fieldTemplateRepository.deleteAll(cardFields);
        }

        // 3. Удаляем шаблон
        cardTemplateRepository.delete(cardTemplate);
    }

    // POST /api/core/templates/{templateId}/fields - добавить поле в шаблон
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public FieldTemplateDTO addFieldToTemplate(UUID currentUserId,
                                               UUID templateId,
                                               FieldTemplateDTO dto) {

        // Проверяем, что шаблон принадлежит текущему пользователю
        cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        FieldTemplateDTO normalized = new FieldTemplateDTO(
                null,
                templateId,
                dto.name(),
                dto.label(),
                dto.fieldType(),
                dto.isRequired(),
                dto.isOnFront(),
                dto.orderIndex(),
                dto.defaultValue(),
                dto.helpText()
        );

        FieldTemplateEntity entity = toFieldTemplateEntity(normalized);
        FieldTemplateEntity saved = fieldTemplateRepository.save(entity);

        return toFieldTemplateDTO(saved);
    }

    // PATCH /api/core/templates/{templateId}/fields/{fieldId} - частично изменить поле в шаблоне
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public FieldTemplateDTO partiallyChangeFieldTemplate(UUID currentUserId,
                                                         UUID templateId,
                                                         UUID fieldId,
                                                         FieldTemplateDTO dto) {

        // Проверяем права на шаблон
        cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        FieldTemplateEntity field = fieldTemplateRepository
                .findByFieldIdAndTemplateId(fieldId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Field not found in template: " + fieldId
                ));

        // Частичное обновление
        if (dto.name() != null) {
            field.setName(dto.name());
        }
        if (dto.label() != null) {
            field.setLabel(dto.label());
        }
        if (dto.fieldType() != null) {
            field.setFieldType(dto.fieldType());
        }

        field.setRequired(dto.isRequired());
        field.setOnFront(dto.isOnFront());

        if (dto.orderIndex() != null) {
            field.setOrderIndex(dto.orderIndex());
        }
        if (dto.defaultValue() != null) {
            field.setDefaultValue(dto.defaultValue());
        }
        if (dto.helpText() != null) {
            field.setHelpText(dto.helpText());
        }

        FieldTemplateEntity saved = fieldTemplateRepository.save(field);

        return toFieldTemplateDTO(saved);
    }

    // DELETE /api/core/templates/{templateId}/fields/{fieldId} - удалить поле из шаблона
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteFieldFromTemplate(UUID currentUserId,
                                        UUID templateId,
                                        UUID fieldId) {

        // Проверяем права на шаблон
        cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        FieldTemplateEntity field = fieldTemplateRepository
                .findByFieldIdAndTemplateId(fieldId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Field not found in template: " + fieldId
                ));

        fieldTemplateRepository.delete(field);
    }

    // UTILS

    private FieldTemplateEntity toFieldTemplateEntity(FieldTemplateDTO dto) {
        Integer effectiveOrderIndex = dto.orderIndex() != null ? dto.orderIndex() : 0;

        return new FieldTemplateEntity(
                dto.fieldId(),
                dto.templateId(),
                dto.name(),
                dto.label(),
                dto.fieldType(),
                dto.isRequired(),
                dto.isOnFront(),
                effectiveOrderIndex,
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

    private Page<CardTemplateDTO> mapTemplatePage(Page<CardTemplateEntity> templatePage) {
        List<CardTemplateEntity> templates = templatePage.getContent();
        if (templates.isEmpty()) {
            return new PageImpl<>(List.of(), templatePage.getPageable(), templatePage.getTotalElements());
        }

        List<UUID> templateIds = templates.stream()
                .map(CardTemplateEntity::getTemplateId)
                .toList();

        List<FieldTemplateEntity> fieldEntities = fieldTemplateRepository.findByTemplateIdIn(templateIds);

        Map<UUID, List<FieldTemplateDTO>> fieldsByTemplateId = fieldEntities.stream()
                .collect(Collectors.groupingBy(
                        FieldTemplateEntity::getTemplateId,
                        Collectors.mapping(this::toFieldTemplateDTO, Collectors.toList())
                ));

        List<CardTemplateDTO> dtoList = templates.stream()
                .map(entity -> toCardTemplateDTO(
                        entity,
                        fieldsByTemplateId.getOrDefault(entity.getTemplateId(), List.of())
                ))
                .toList();

        return new PageImpl<>(dtoList, templatePage.getPageable(), templatePage.getTotalElements());
    }
}

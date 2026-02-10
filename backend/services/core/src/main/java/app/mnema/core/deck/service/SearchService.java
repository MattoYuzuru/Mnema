package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.CardTemplateVersionEntity;
import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.CardTemplateVersionRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;
    private final CardTemplateRepository cardTemplateRepository;
    private final FieldTemplateRepository fieldTemplateRepository;
    private final CardTemplateVersionRepository cardTemplateVersionRepository;

    public SearchService(UserDeckRepository userDeckRepository,
                         UserCardRepository userCardRepository,
                         PublicCardRepository publicCardRepository,
                         CardTemplateRepository cardTemplateRepository,
                         FieldTemplateRepository fieldTemplateRepository,
                         CardTemplateVersionRepository cardTemplateVersionRepository) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
        this.cardTemplateRepository = cardTemplateRepository;
        this.fieldTemplateRepository = fieldTemplateRepository;
        this.cardTemplateVersionRepository = cardTemplateVersionRepository;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<UserDeckDTO> searchUserDecks(UUID currentUserId,
                                             String query,
                                             List<String> tags,
                                             int page,
                                             int limit) {
        Pageable pageable = toPageable(page, limit);
        String normalizedQuery = normalizeQuery(query);
        String tagsCsv = normalizeTags(tags);

        if (normalizedQuery == null && tagsCsv == null) {
            return Page.empty(pageable);
        }

        Page<UserDeckEntity> decks = normalizedQuery == null
                ? userDeckRepository.searchUserDecksByTags(currentUserId, tagsCsv, pageable)
                : userDeckRepository.searchUserDecks(currentUserId, normalizedQuery, tagsCsv, pageable);

        return decks.map(this::toUserDeckDTO);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<UserCardDTO> searchUserCards(UUID currentUserId,
                                             UUID userDeckId,
                                             String query,
                                             List<String> tags,
                                             int page,
                                             int limit) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        Pageable pageable = toPageable(page, limit);
        String normalizedQuery = normalizeQuery(query);
        String tagsCsv = normalizeTags(tags);

        if (normalizedQuery == null && tagsCsv == null) {
            return Page.empty(pageable);
        }

        Page<UserCardEntity> cards = normalizedQuery == null
                ? userCardRepository.searchUserCardsByTags(currentUserId, userDeckId, tagsCsv, pageable)
                : userCardRepository.searchUserCards(currentUserId, userDeckId, normalizedQuery, tagsCsv, pageable);

        return mapUserCardPage(cards);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<CardTemplateDTO> searchTemplates(UUID currentUserId,
                                                 String query,
                                                 String scope,
                                                 int page,
                                                 int limit) {
        Pageable pageable = toPageable(page, limit);
        String normalizedQuery = normalizeQuery(query);

        if (normalizedQuery == null) {
            return Page.empty(pageable);
        }

        String normalizedScope = scope == null ? "public" : scope.trim().toLowerCase();

        Page<CardTemplateEntity> templates;
        if ("public".equalsIgnoreCase(normalizedScope)) {
            templates = cardTemplateRepository.searchPublicTemplates(normalizedQuery, pageable);
        } else if ("mine".equalsIgnoreCase(normalizedScope)) {
            templates = cardTemplateRepository.searchUserTemplates(currentUserId, normalizedQuery, pageable);
        } else if ("all".equalsIgnoreCase(normalizedScope)) {
            templates = cardTemplateRepository.searchUserAndPublicTemplates(currentUserId, normalizedQuery, pageable);
        } else {
            throw new IllegalArgumentException("Unknown template scope: " + scope);
        }

        return mapTemplatePage(templates);
    }

    private Pageable toPageable(int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }
        return PageRequest.of(page - 1, limit);
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> normalized = tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    private Page<UserCardDTO> mapUserCardPage(Page<UserCardEntity> cardsPage) {
        List<UserCardEntity> cards = cardsPage.getContent();
        if (cards.isEmpty()) {
            return new PageImpl<>(List.of(), cardsPage.getPageable(), cardsPage.getTotalElements());
        }

        Map<UUID, PublicCardEntity> publicCardsById = resolvePublicCards(cards);

        List<UserCardDTO> dtoList = cards.stream()
                .map(card -> toUserCardDTO(card, publicCardsById))
                .toList();

        return new PageImpl<>(dtoList, cardsPage.getPageable(), cardsPage.getTotalElements());
    }

    private Map<UUID, PublicCardEntity> resolvePublicCards(List<UserCardEntity> cards) {
        List<UUID> publicCardIds = cards.stream()
                .map(UserCardEntity::getPublicCardId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (publicCardIds.isEmpty()) {
            return Map.of();
        }

        List<PublicCardEntity> cards = publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(publicCardIds);
        Map<UUID, PublicCardEntity> map = new HashMap<>();
        for (PublicCardEntity card : cards) {
            map.putIfAbsent(card.getCardId(), card);
        }
        return map;
    }

    private UserCardDTO toUserCardDTO(UserCardEntity card, Map<UUID, PublicCardEntity> publicCardsById) {
        JsonNode effectiveContent = buildEffectiveContent(card, publicCardsById);
        String[] tags = buildEffectiveTags(card, publicCardsById);

        return new UserCardDTO(
                card.getUserCardId(),
                card.getPublicCardId(),
                card.isCustom(),
                card.isDeleted(),
                card.getPersonalNote(),
                tags,
                effectiveContent
        );
    }

    private JsonNode buildEffectiveContent(UserCardEntity card, Map<UUID, PublicCardEntity> publicCardsById) {
        JsonNode override = card.getContentOverride();
        if (card.getPublicCardId() == null) {
            return override;
        }

        PublicCardEntity publicCard = publicCardsById.get(card.getPublicCardId());
        JsonNode base = publicCard == null ? null : publicCard.getContent();
        return mergeJson(base, override);
    }

    private String[] buildEffectiveTags(UserCardEntity card, Map<UUID, PublicCardEntity> publicCardsById) {
        if (card.getTags() != null) {
            return card.getTags();
        }
        if (card.getPublicCardId() == null) {
            return null;
        }
        PublicCardEntity publicCard = publicCardsById.get(card.getPublicCardId());
        return publicCard == null ? null : publicCard.getTags();
    }

    private JsonNode mergeJson(JsonNode base, JsonNode override) {
        if (override == null || override.isNull()) {
            return base;
        }
        if (base == null || base.isNull()) {
            return override;
        }

        if (base instanceof ObjectNode baseObj && override instanceof ObjectNode overrideObj) {
            ObjectNode merged = baseObj.deepCopy();
            merged.setAll(overrideObj);
            return merged;
        }

        return override;
    }

    private Page<CardTemplateDTO> mapTemplatePage(Page<CardTemplateEntity> templatePage) {
        List<CardTemplateEntity> templates = templatePage.getContent();
        if (templates.isEmpty()) {
            return new PageImpl<>(List.of(), templatePage.getPageable(), templatePage.getTotalElements());
        }

        List<UUID> templateIds = templates.stream()
                .map(CardTemplateEntity::getTemplateId)
                .toList();

        List<CardTemplateVersionEntity> versions = cardTemplateVersionRepository.findByTemplateIdIn(templateIds);
        Map<UUID, CardTemplateVersionEntity> latestByTemplate = new java.util.HashMap<>();
        for (CardTemplateVersionEntity version : versions) {
            if (version == null) {
                continue;
            }
            CardTemplateVersionEntity existing = latestByTemplate.get(version.getTemplateId());
            if (existing == null || version.getVersion() > existing.getVersion()) {
                latestByTemplate.put(version.getTemplateId(), version);
            }
        }

        List<FieldTemplateEntity> fieldEntities = fieldTemplateRepository.findByTemplateIdIn(templateIds);

        Map<UUID, List<FieldTemplateDTO>> fieldsByTemplateId = fieldEntities.stream()
                .filter(field -> {
                    CardTemplateVersionEntity latest = latestByTemplate.get(field.getTemplateId());
                    return latest != null && field.getTemplateVersion() != null
                            && field.getTemplateVersion().equals(latest.getVersion());
                })
                .collect(Collectors.groupingBy(
                        FieldTemplateEntity::getTemplateId,
                        Collectors.mapping(this::toFieldTemplateDTO, Collectors.toList())
                ));

        List<CardTemplateDTO> dtoList = templates.stream()
                .map(entity -> toCardTemplateDTO(
                        entity,
                        latestByTemplate.get(entity.getTemplateId()),
                        fieldsByTemplateId.getOrDefault(entity.getTemplateId(), List.of())
                ))
                .toList();

        return new PageImpl<>(dtoList, templatePage.getPageable(), templatePage.getTotalElements());
    }

    private CardTemplateDTO toCardTemplateDTO(CardTemplateEntity entity,
                                              CardTemplateVersionEntity version,
                                              List<FieldTemplateDTO> fields) {
        Integer effectiveVersion = version != null ? version.getVersion() : entity.getLatestVersion();
        return new CardTemplateDTO(
                entity.getTemplateId(),
                effectiveVersion,
                entity.getLatestVersion(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getDescription(),
                entity.isPublic(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                version != null ? version.getLayout() : entity.getLayout(),
                version != null ? version.getAiProfile() : entity.getAiProfile(),
                version != null ? version.getIconUrl() : entity.getIconUrl(),
                fields
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

    private UserDeckDTO toUserDeckDTO(UserDeckEntity entity) {
        return new UserDeckDTO(
                entity.getUserDeckId(),
                entity.getUserId(),
                entity.getPublicDeckId(),
                entity.getSubscribedVersion(),
                entity.getCurrentVersion(),
                entity.getTemplateVersion(),
                entity.getSubscribedTemplateVersion(),
                entity.isAutoUpdate(),
                entity.getAlgorithmId(),
                entity.getAlgorithmParams(),
                entity.getDisplayName(),
                entity.getDisplayDescription(),
                entity.getCreatedAt(),
                entity.getLastSyncedAt(),
                entity.isArchived()
        );
    }
}

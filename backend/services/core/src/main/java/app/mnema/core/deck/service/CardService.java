package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.dto.MissingFieldSummaryDTO;
import app.mnema.core.deck.domain.dto.MissingFieldStatDTO;
import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.DuplicateGroupDTO;
import app.mnema.core.deck.domain.entity.*;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.request.DuplicateSearchRequest;
import app.mnema.core.deck.domain.request.MissingFieldCardsRequest;
import app.mnema.core.deck.domain.request.MissingFieldSummaryRequest;
import app.mnema.core.deck.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CardService {

    private static final int MAX_TAGS = 3;
    private static final int MAX_TAG_LENGTH = 25;

    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;
    private final PublicDeckRepository publicDeckRepository;
    private final FieldTemplateRepository fieldTemplateRepository;
    private final ObjectMapper objectMapper;

    public CardService(UserDeckRepository userDeckRepository,
                       UserCardRepository userCardRepository,
                       PublicCardRepository publicCardRepository,
                       PublicDeckRepository publicDeckRepository,
                       FieldTemplateRepository fieldTemplateRepository,
                       ObjectMapper objectMapper) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
        this.publicDeckRepository = publicDeckRepository;
        this.fieldTemplateRepository = fieldTemplateRepository;
        this.objectMapper = objectMapper;
    }

    // Просмотр всех карт в пользовательской колоде
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<UserCardDTO> getUserCardsByDeck(UUID currentUserId, UUID userDeckId, int page, int limit) {

        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        return userCardRepository
                .findByUserDeckIdAndDeletedFalseOrderByCreatedAtAsc(userDeckId, pageable)
                .map(this::toUserCardDTO);
    }

    // Получить одну карту юзера
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public UserCardDTO getUserCard(UUID currentUserId, UUID userDeckId, UUID userCardId) {
        UserCardEntity card = userCardRepository.findById(userCardId)
                .orElseThrow(() -> new IllegalArgumentException("User card not found: " + userCardId));

        if (!card.getUserDeckId().equals(userDeckId) || !card.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to card " + userCardId);
        }

        return toUserCardDTO(card);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public MissingFieldSummaryDTO getMissingFieldSummary(UUID currentUserId,
                                                         UUID userDeckId,
                                                         MissingFieldSummaryRequest request) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));
        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }
        if (request == null || request.fields() == null || request.fields().isEmpty()) {
            throw new IllegalArgumentException("fields are required");
        }
        int sampleLimit = request.sampleLimit() == null ? 0 : Math.max(0, Math.min(request.sampleLimit(), 20));
        List<String> fields = request.fields().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields are required");
        }

        List<MissingFieldStatDTO> stats = new ArrayList<>();
        for (String field : fields) {
            long missingCount = userCardRepository.countMissingField(currentUserId, userDeckId, field);
            List<UserCardDTO> samples = sampleLimit == 0
                    ? List.of()
                    : loadMissingSamples(currentUserId, userDeckId, field, sampleLimit);
            stats.add(new MissingFieldStatDTO(field, missingCount, samples));
        }
        return new MissingFieldSummaryDTO(stats, sampleLimit);
    }

    private List<UserCardDTO> loadMissingSamples(UUID userId, UUID userDeckId, String field, int limit) {
        List<UUID> ids = userCardRepository.findMissingFieldCardIds(userId, userDeckId, field, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UserCardEntity> cards = userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(userId, userDeckId, ids);
        if (cards.isEmpty()) {
            return List.of();
        }
        Map<UUID, UserCardEntity> byId = cards.stream()
                .collect(java.util.stream.Collectors.toMap(UserCardEntity::getUserCardId, card -> card));
        List<UserCardDTO> ordered = new ArrayList<>();
        for (UUID id : ids) {
            UserCardEntity card = byId.get(id);
            if (card != null) {
                ordered.add(toUserCardDTO(card));
            }
        }
        return ordered.isEmpty() ? List.of() : Collections.unmodifiableList(ordered);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public List<UserCardDTO> getMissingFieldCards(UUID currentUserId,
                                                  UUID userDeckId,
                                                  MissingFieldCardsRequest request) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));
        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }
        if (request == null || request.fields() == null || request.fields().isEmpty()) {
            throw new IllegalArgumentException("fields are required");
        }
        int limit = request.limit() == null ? 50 : Math.max(1, Math.min(request.limit(), 200));
        List<String> fields = request.fields().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields are required");
        }

        java.util.LinkedHashSet<UUID> merged = new java.util.LinkedHashSet<>();
        for (String field : fields) {
            if (merged.size() >= limit) {
                break;
            }
            int remaining = limit - merged.size();
            List<UUID> ids = userCardRepository.findMissingFieldCardIds(currentUserId, userDeckId, field, remaining);
            merged.addAll(ids);
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        List<UserCardEntity> cards = userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(
                currentUserId, userDeckId, new ArrayList<>(merged));
        if (cards.isEmpty()) {
            return List.of();
        }
        Map<UUID, UserCardEntity> byId = cards.stream()
                .collect(java.util.stream.Collectors.toMap(UserCardEntity::getUserCardId, card -> card));
        List<UserCardDTO> ordered = new ArrayList<>();
        for (UUID id : merged) {
            UserCardEntity card = byId.get(id);
            if (card != null) {
                ordered.add(toUserCardDTO(card));
            }
        }
        return ordered.isEmpty() ? List.of() : Collections.unmodifiableList(ordered);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public List<DuplicateGroupDTO> getDuplicateGroups(UUID currentUserId,
                                                      UUID userDeckId,
                                                      DuplicateSearchRequest request) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));
        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }
        if (request == null || request.fields() == null || request.fields().isEmpty()) {
            throw new IllegalArgumentException("fields are required");
        }
        int limitGroups = request.limitGroups() == null ? 10 : Math.max(1, Math.min(request.limitGroups(), 50));
        int perGroupLimit = request.perGroupLimit() == null ? 5 : Math.max(2, Math.min(request.perGroupLimit(), 20));
        List<String> fields = request.fields().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields are required");
        }

        List<UserCardRepository.DuplicateGroupProjection> groups = userCardRepository.findDuplicateGroups(
                currentUserId,
                userDeckId,
                fields.toArray(String[]::new),
                limitGroups
        );
        if (groups.isEmpty()) {
            return List.of();
        }

        List<DuplicateGroupDTO> result = new ArrayList<>();
        for (UserCardRepository.DuplicateGroupProjection group : groups) {
            UUID[] ids = group.getCardIds();
            if (ids == null || ids.length == 0) {
                continue;
            }
            List<UUID> limitedIds = new ArrayList<>();
            for (int i = 0; i < ids.length && limitedIds.size() < perGroupLimit; i++) {
                limitedIds.add(ids[i]);
            }
            List<UserCardEntity> cards = userCardRepository.findByUserIdAndUserDeckIdAndUserCardIdIn(
                    currentUserId,
                    userDeckId,
                    limitedIds
            );
            if (cards.isEmpty()) {
                continue;
            }
            Map<UUID, UserCardEntity> byId = cards.stream()
                    .collect(Collectors.toMap(UserCardEntity::getUserCardId, card -> card));
            List<UserCardDTO> ordered = new ArrayList<>();
            for (UUID id : limitedIds) {
                UserCardEntity card = byId.get(id);
                if (card != null) {
                    ordered.add(toUserCardDTO(card));
                }
            }
            if (!ordered.isEmpty()) {
                result.add(new DuplicateGroupDTO(group.getCnt(), Collections.unmodifiableList(ordered)));
            }
        }
        return result.isEmpty() ? List.of() : Collections.unmodifiableList(result);
    }

    // Просмотр публичных карт колоды по deck_id + version (если version null, берём последнюю)
    @Transactional(readOnly = true)
    public Page<PublicCardDTO> getPublicCards(UUID deckId, Integer deckVersion, int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        PublicDeckEntity deck = resolvePublicDeck(deckId, deckVersion);

        if (!deck.isPublicFlag()) {
            throw new SecurityException("Deck is not public: " + deckId);
        }

        return publicCardRepository
                .findByDeckIdAndDeckVersionAndActiveTrueOrderByOrderIndex(
                        deck.getDeckId(),
                        deck.getVersion(),
                        pageable
                )
                .map(this::toPublicCardDTO);
    }

    // Получить одну публичную карту по cardId
    @Transactional(readOnly = true)
    public PublicCardDTO getPublicCardById(UUID cardId) {
        PublicCardEntity card = publicCardRepository.findByCardId(cardId)
                .orElseThrow(() -> new IllegalArgumentException("Public card not found: " + cardId));

        PublicDeckEntity deck = publicDeckRepository
                .findByDeckIdAndVersion(card.getDeckId(), card.getDeckVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "Public deck not found for card: deckId=" + card.getDeckId() + ", version=" + card.getDeckVersion()
                ));

        if (!deck.isPublicFlag()) {
            throw new SecurityException("Deck is not public: " + card.getDeckId());
        }

        return toPublicCardDTO(card);
    }

    // Список полей шаблона для публичной колоды (для фронта, чтобы не дублировать template в каждой карте)
    @Transactional(readOnly = true)
    public List<FieldTemplateDTO> getFieldTemplatesForPublicDeck(UUID deckId, Integer deckVersion) {
        PublicDeckEntity deck = resolvePublicDeck(deckId, deckVersion);

        if (!deck.isPublicFlag()) {
            throw new SecurityException("Deck is not public: " + deckId);
        }

        UUID templateId = deck.getTemplateId();
        if (templateId == null) {
            return List.of();
        }

        return fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, deck.getTemplateVersion())
                .stream()
                .map(this::toFieldTemplateDTO)
                .toList();
    }

    private PublicDeckEntity resolvePublicDeck(UUID deckId, Integer deckVersion) {
        if (deckVersion == null) {
            return publicDeckRepository
                    .findLatestByDeckId(deckId)
                    .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + deckId));
        } else {
            return publicDeckRepository
                    .findByDeckIdAndVersion(deckId, deckVersion)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Public deck not found: deckId=" + deckId + ", version=" + deckVersion
                    ));
        }
    }

    // Добавление одной карты в пользовательскую колоду (обёртка над батчем)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserCardDTO addNewCardToDeck(UUID currentUserId,
                                        UUID userDeckId,
                                        CreateCardRequest request) {
        List<UserCardDTO> created = addNewCardsToDeckBatch(currentUserId, userDeckId, List.of(request));
        return created.getFirst();
    }

    // Добавление батча карт в пользовательскую колоду.
    // Для автора публичной колоды: создаётся НОВАЯ версия public_decks,
    // копируются старые карты и добавляются новые, версия увеличивается на 1.
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public List<UserCardDTO> addNewCardsToDeckBatch(UUID currentUserId,
                                                    UUID userDeckId,
                                                    List<CreateCardRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        UserDeckEntity userDeck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!userDeck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        Instant now = Instant.now();
        List<UserCardDTO> result = new ArrayList<>();

        // 1) Локальная колода
        if (userDeck.getPublicDeckId() == null) {
            long offsetNanos = 0L;
            for (CreateCardRequest request : requests) {
                validateTags(request.tags());
                JsonNode content = request.contentOverride() != null ? request.contentOverride() : request.content();
                if (content == null || content.isNull()) {
                    throw new IllegalArgumentException("Card content must not be null");
                }

                Instant createdAt = now.plusNanos(offsetNanos++);
                UserCardEntity userCard = new UserCardEntity(
                        currentUserId,
                        userDeckId,
                        null,
                        true,
                        false,
                        request.personalNote(),
                        request.tags(),
                        content,
                        createdAt,
                        null
                );

                result.add(toUserCardDTO(userCardRepository.save(userCard)));
            }
            return result;
        }

        UUID publicDeckId = userDeck.getPublicDeckId();

        PublicDeckEntity latestDeck = publicDeckRepository
                .findLatestByDeckId(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));

        // 2) Не автор: добавляем только кастомные карты
        if (!latestDeck.getAuthorId().equals(currentUserId)) {
            long offsetNanos = 0L;
            for (CreateCardRequest request : requests) {
                validateTags(request.tags());
                JsonNode content = request.contentOverride() != null ? request.contentOverride() : request.content();
                if (content == null || content.isNull()) {
                    throw new IllegalArgumentException("Card content must not be null");
                }

                Instant createdAt = now.plusNanos(offsetNanos++);
                UserCardEntity userCard = new UserCardEntity(
                        currentUserId,
                        userDeckId,
                        null,
                        true,
                        false,
                        request.personalNote(),
                        request.tags(),
                        content,
                        createdAt,
                        null
                );

                result.add(toUserCardDTO(userCardRepository.save(userCard)));
            }
            return result;
        }

    /*
      3) Автор публичной колоды: создаём новую версию public_decks как immutable snapshot.
      Ключевые правила:
      - created_at у public_decks должен отражать создание "логической колоды" и не меняться между версиями.
      - public_cards в каждой версии содержат полный набор карт этой версии (snapshot).
      - checksum обязателен для корректного sync и вычисляется на сервере по content.
      - order_index для добавляемых карт выставляется в конец, чтобы не плодить коллизии 1/2, 1/2 и т.п.
     */

        int newVersion = latestDeck.getVersion() + 1;

        PublicDeckEntity newDeckVersion = new PublicDeckEntity(
                latestDeck.getDeckId(),
                newVersion,
                latestDeck.getAuthorId(),
                latestDeck.getName(),
                latestDeck.getDescription(),
                latestDeck.getIconMediaId(),
                latestDeck.getTemplateId(),
                latestDeck.getTemplateVersion(),
                latestDeck.isPublicFlag(),
                latestDeck.isListed(),
                latestDeck.getLanguageCode(),
                latestDeck.getTags(),
                latestDeck.getCreatedAt(), // важно: не now
                now,                       // updated_at
                now,                       // published_at
                latestDeck.getForkedFromDeck()
        );

        PublicDeckEntity savedNewDeck = publicDeckRepository.save(newDeckVersion);

        // Клонируем карты из предыдущей версии
        List<PublicCardEntity> oldCards = publicCardRepository
                .findByDeckIdAndDeckVersion(latestDeck.getDeckId(), latestDeck.getVersion());

        List<PublicCardEntity> clonedOldCards = new ArrayList<>(oldCards.size());
        int maxOrderIndex = 0;

        for (PublicCardEntity old : oldCards) {
            Integer oi = old.getOrderIndex();
            if (oi != null && oi > maxOrderIndex) {
                maxOrderIndex = oi;
            }

            String checksum = normalizeChecksum(old.getChecksum());
            if (checksum == null) {
                checksum = computeChecksum(old.getContent());
            }

            PublicCardEntity clone = new PublicCardEntity(
                    savedNewDeck.getDeckId(),
                    savedNewDeck.getVersion(),
                    savedNewDeck,
                    old.getContent(),
                    old.getOrderIndex(),
                    old.getTags(),
                    old.getCreatedAt(),
                    old.getUpdatedAt(),
                    old.isActive(),
                    checksum
            );
            clonedOldCards.add(clone);
        }

        if (!clonedOldCards.isEmpty()) {
            publicCardRepository.saveAll(clonedOldCards);
        }

        // Добавляем новые публичные карты в конец
        int nextOrderIndex = maxOrderIndex + 1;

        List<PublicCardEntity> newPublicCards = new ArrayList<>(requests.size());
        for (CreateCardRequest request : requests) {
            validateTags(request.tags());
            if (request.content() == null || request.content().isNull()) {
                throw new IllegalArgumentException("Public card content must not be null");
            }

            String checksum = computeChecksum(request.content());

            PublicCardEntity pc = new PublicCardEntity(
                    savedNewDeck.getDeckId(),
                    savedNewDeck.getVersion(),
                    savedNewDeck,
                    request.content(),
                    nextOrderIndex++,
                    request.tags(),
                    now,
                    null,
                    true,
                    checksum
            );

            newPublicCards.add(pc);
        }

        List<PublicCardEntity> savedNewPublicCards =
                newPublicCards.isEmpty() ? List.of() : publicCardRepository.saveAll(newPublicCards);

        // Обновляем текущую версию в user_decks автора
        userDeck.setCurrentVersion(savedNewDeck.getVersion());
        userDeck.setTemplateVersion(savedNewDeck.getTemplateVersion());
        userDeck.setLastSyncedAt(now);
        userDeckRepository.save(userDeck);

        // Создаём user_cards для новых публичных карт (старые user_cards не трогаем)
        long offsetNanos = 0L;
        for (int i = 0; i < savedNewPublicCards.size(); i++) {
            PublicCardEntity publicCard = savedNewPublicCards.get(i);
            CreateCardRequest request = requests.get(i);

            Instant createdAt = now.plusNanos(offsetNanos++);
            UserCardEntity userCard = new UserCardEntity(
                    currentUserId,
                    userDeckId,
                    publicCard.getCardId(),
                    false,
                    false,
                    request.personalNote(),
                    null,
                    request.contentOverride(),
                    createdAt,
                    null
            );

            result.add(toUserCardDTO(userCardRepository.save(userCard)));
        }

        return result;
    }

    private void validateTags(String[] tags) {
        if (tags == null) {
            return;
        }
        if (tags.length > MAX_TAGS) {
            throw new IllegalArgumentException("Too many tags");
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("Tag is too long");
            }
        }
    }

    /*
      Детерминированный checksum для JSON.
      Мы каноникалим объект: сортируем ключи рекурсивно, массивы оставляем в порядке.
      Это делает checksum стабильным при разных порядках полей.
     */
    private String computeChecksum(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }

        JsonNode canonical = canonicalizeJson(content);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(canonical);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize card content for checksum", e);
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private JsonNode canonicalizeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return NullNode.getInstance();
        }

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            ObjectNode out = JsonNodeFactory.instance.objectNode();

            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);

            for (String name : names) {
                out.set(name, canonicalizeJson(obj.get(name)));
            }
            return out;
        }

        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode el : arr) {
                out.add(canonicalizeJson(el));
            }
            return out;
        }

        return node;
    }

    private String normalizeChecksum(String checksum) {
        if (checksum == null) {
            return null;
        }
        String s = checksum.trim();
        return s.isEmpty() ? null : s;
    }


    // Обновление юзер карты (без SR логики)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserCardDTO updateUserCard(UUID currentUserId,
                                      UUID userDeckId,
                                      UUID userCardId,
                                      UserCardDTO dto,
                                      boolean updateGlobally) {

        UserCardEntity card = userCardRepository.findById(userCardId)
                .orElseThrow(() -> new IllegalArgumentException("User card not found: " + userCardId));

        if (!card.getUserDeckId().equals(userDeckId) || !card.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to card " + userCardId);
        }

        if (updateGlobally) {
            UserDeckEntity userDeck = userDeckRepository.findById(userDeckId)
                    .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));
            if (!userDeck.getUserId().equals(currentUserId)) {
                throw new SecurityException("Access denied to deck " + userDeckId);
            }
            return updateUserCardGlobally(currentUserId, userDeck, card, dto);
        }

        card.setPersonalNote(dto.personalNote());
        // Для простоты считаем, что effectiveContent, присланный с фронта, и есть новый override
        card.setContentOverride(dto.effectiveContent());
        card.setDeleted(dto.isDeleted());

        if (dto.tags() != null) {
            validateTags(dto.tags());
            if (card.getPublicCardId() != null) {
                var publicCardOpt = publicCardRepository.findByCardId(card.getPublicCardId());
                if (publicCardOpt.isPresent() && tagsEqual(dto.tags(), publicCardOpt.get().getTags())) {
                    card.setTags(null);
                } else {
                    card.setTags(dto.tags());
                }
            } else {
                card.setTags(dto.tags());
            }
        }

        card.setUpdatedAt(Instant.now());

        UserCardEntity saved = userCardRepository.save(card);
        return toUserCardDTO(saved);
    }

    private UserCardDTO updateUserCardGlobally(UUID currentUserId,
                                               UserDeckEntity userDeck,
                                               UserCardEntity card,
                                               UserCardDTO dto) {
        UUID publicDeckId = userDeck.getPublicDeckId();
        if (publicDeckId == null) {
            throw new IllegalStateException("Local deck has no public source");
        }
        if (card.getPublicCardId() == null) {
            throw new IllegalArgumentException("Custom card cannot be updated globally");
        }

        PublicDeckEntity latestDeck = publicDeckRepository.findLatestByDeckId(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));
        if (!latestDeck.getAuthorId().equals(currentUserId)) {
            throw new SecurityException("Access denied to public deck " + publicDeckId);
        }

        PublicCardEntity linkedPublicCard = publicCardRepository.findByCardId(card.getPublicCardId())
                .orElseThrow(() -> new IllegalArgumentException("Public card not found: " + card.getPublicCardId()));

        List<PublicCardEntity> latestCards = publicCardRepository
                .findByDeckIdAndDeckVersion(publicDeckId, latestDeck.getVersion());

        String targetChecksum = normalizeChecksum(linkedPublicCard.getChecksum());
        if (targetChecksum == null) {
            targetChecksum = computeChecksum(linkedPublicCard.getContent());
        }

        UUID matchingCardId = null;
        int checksumMatches = 0;
        PublicCardEntity checksumMatchCard = null;
        PublicCardEntity cardIdMatch = null;

        for (PublicCardEntity pc : latestCards) {
            if (pc.getCardId().equals(card.getPublicCardId())) {
                matchingCardId = pc.getCardId();
                cardIdMatch = pc;
            }

            String latestChecksum = normalizeChecksum(pc.getChecksum());
            if (latestChecksum == null) {
                latestChecksum = computeChecksum(pc.getContent());
            }

            if (targetChecksum != null && targetChecksum.equals(latestChecksum)) {
                checksumMatches++;
                checksumMatchCard = pc;
            }
        }

        boolean matchByCardId = matchingCardId != null;
        if (!matchByCardId) {
            if (targetChecksum == null) {
                throw new IllegalStateException("Public card checksum missing for update");
            }
            if (checksumMatches == 0) {
                throw new IllegalStateException("Public card not found in latest version");
            }
            if (checksumMatches > 1) {
                throw new IllegalStateException("Public card checksum is not unique in latest version");
            }
        }

        PublicCardEntity targetLatestCard = matchByCardId ? cardIdMatch : checksumMatchCard;
        if (targetLatestCard == null) {
            throw new IllegalStateException("Public card not found in latest version");
        }

        JsonNode updatedContent = dto.effectiveContent();
        if (updatedContent == null || updatedContent.isNull()) {
            throw new IllegalArgumentException("Updated content must not be null");
        }
        if (!updatedContent.isObject()) {
            throw new IllegalArgumentException("Updated content must be an object");
        }

        String[] updatedTags = dto.tags() != null ? dto.tags() : targetLatestCard.getTags();
        validateTags(updatedTags);
        String updatedChecksum = computeChecksum(updatedContent);

        Instant now = Instant.now();
        int newVersion = latestDeck.getVersion() + 1;

        PublicDeckEntity newDeckVersion = new PublicDeckEntity(
                latestDeck.getDeckId(),
                newVersion,
                latestDeck.getAuthorId(),
                latestDeck.getName(),
                latestDeck.getDescription(),
                latestDeck.getIconMediaId(),
                latestDeck.getTemplateId(),
                latestDeck.getTemplateVersion(),
                latestDeck.isPublicFlag(),
                latestDeck.isListed(),
                latestDeck.getLanguageCode(),
                latestDeck.getTags(),
                latestDeck.getCreatedAt(),
                now,
                now,
                latestDeck.getForkedFromDeck()
        );

        PublicDeckEntity savedNewDeck = publicDeckRepository.save(newDeckVersion);

        List<PublicCardEntity> clonedCards = new ArrayList<>(latestCards.size());
        PublicCardEntity updatedClone = null;

        for (PublicCardEntity old : latestCards) {
            String oldChecksum = normalizeChecksum(old.getChecksum());
            if (oldChecksum == null) {
                oldChecksum = computeChecksum(old.getContent());
            }
            boolean isTarget = matchByCardId
                    ? old.getCardId().equals(matchingCardId)
                    : targetChecksum != null && targetChecksum.equals(oldChecksum);

            JsonNode content = isTarget ? updatedContent : old.getContent();
            String[] tags = isTarget ? updatedTags : old.getTags();
            String checksum = isTarget ? updatedChecksum : oldChecksum;

            PublicCardEntity clone = new PublicCardEntity(
                    savedNewDeck.getDeckId(),
                    savedNewDeck.getVersion(),
                    savedNewDeck,
                    content,
                    old.getOrderIndex(),
                    tags,
                    old.getCreatedAt(),
                    isTarget ? now : old.getUpdatedAt(),
                    old.isActive(),
                    checksum
            );
            if (isTarget) {
                updatedClone = clone;
            }
            clonedCards.add(clone);
        }

        if (!clonedCards.isEmpty()) {
            publicCardRepository.saveAll(clonedCards);
        }

        if (updatedClone == null || updatedClone.getCardId() == null) {
            throw new IllegalStateException("Failed to create updated public card");
        }

        userDeck.setCurrentVersion(savedNewDeck.getVersion());
        userDeck.setTemplateVersion(savedNewDeck.getTemplateVersion());
        userDeck.setLastSyncedAt(now);
        userDeckRepository.save(userDeck);

        card.setPersonalNote(dto.personalNote());
        card.setContentOverride(null);
        card.setDeleted(dto.isDeleted());
        card.setPublicCardId(updatedClone.getCardId());
        card.setTags(null);
        card.setUpdatedAt(now);

        UserCardEntity saved = userCardRepository.save(card);
        return toUserCardDTO(saved);
    }

    // Логическое удаление юзер карты
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteUserCard(UUID currentUserId, UUID userDeckId, UUID userCardId) {
        deleteUserCard(currentUserId, userDeckId, userCardId, false);
    }

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteUserCard(UUID currentUserId,
                               UUID userDeckId,
                               UUID userCardId,
                               boolean deleteGlobally) {
        UserDeckEntity userDeck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!userDeck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        UserCardEntity card = userCardRepository.findById(userCardId)
                .orElseThrow(() -> new IllegalArgumentException("User card not found: " + userCardId));

        if (!card.getUserDeckId().equals(userDeckId) || !card.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to card " + userCardId);
        }

        Instant now = Instant.now();

        if (!deleteGlobally) {
            card.setDeleted(true);
            card.setUpdatedAt(now);
            userCardRepository.save(card);
            return;
        }

        UUID publicDeckId = userDeck.getPublicDeckId();
        if (publicDeckId == null) {
            throw new IllegalStateException("Local deck has no public source");
        }

        PublicDeckEntity latestDeck = publicDeckRepository
                .findLatestByDeckId(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));

        if (!latestDeck.getAuthorId().equals(currentUserId)) {
            throw new SecurityException("Access denied to public deck " + publicDeckId);
        }

        UUID publicCardId = card.getPublicCardId();
        if (publicCardId == null) {
            throw new IllegalArgumentException("Custom card cannot be deleted globally");
        }

        PublicCardEntity linkedPublicCard = publicCardRepository.findByCardId(publicCardId)
                .orElseThrow(() -> new IllegalArgumentException("Public card not found: " + publicCardId));

        if (!linkedPublicCard.getDeckId().equals(publicDeckId)) {
            throw new IllegalArgumentException("Public card does not belong to deck " + publicDeckId);
        }

        List<PublicCardEntity> latestCards = publicCardRepository
                .findByDeckIdAndDeckVersion(publicDeckId, latestDeck.getVersion());

        String targetChecksum = normalizeChecksum(linkedPublicCard.getChecksum());
        if (targetChecksum == null) {
            targetChecksum = computeChecksum(linkedPublicCard.getContent());
        }

        UUID matchingCardId = null;
        int checksumMatches = 0;
        PublicCardEntity checksumMatchCard = null;

        for (PublicCardEntity pc : latestCards) {
            if (pc.getCardId().equals(publicCardId)) {
                matchingCardId = publicCardId;
            }

            String latestChecksum = normalizeChecksum(pc.getChecksum());
            if (latestChecksum == null) {
                latestChecksum = computeChecksum(pc.getContent());
            }

            if (targetChecksum != null && targetChecksum.equals(latestChecksum)) {
                checksumMatches++;
                checksumMatchCard = pc;
            }
        }

        boolean matchByCardId = matchingCardId != null;
        if (!matchByCardId) {
            if (targetChecksum == null) {
                throw new IllegalStateException("Public card checksum missing for deletion");
            }
            if (checksumMatches == 0) {
                throw new IllegalStateException("Public card not found in latest version");
            }
            if (checksumMatches > 1) {
                throw new IllegalStateException("Public card checksum is not unique in latest version");
            }
        }

        boolean alreadyInactive = false;
        if (matchByCardId) {
            for (PublicCardEntity pc : latestCards) {
                if (pc.getCardId().equals(matchingCardId)) {
                    alreadyInactive = !pc.isActive();
                    break;
                }
            }
        } else if (checksumMatchCard != null) {
            alreadyInactive = !checksumMatchCard.isActive();
        }

        card.setDeleted(true);
        card.setUpdatedAt(now);

        if (alreadyInactive) {
            userCardRepository.save(card);
            return;
        }

        int newVersion = latestDeck.getVersion() + 1;

        PublicDeckEntity newDeckVersion = new PublicDeckEntity(
                latestDeck.getDeckId(),
                newVersion,
                latestDeck.getAuthorId(),
                latestDeck.getName(),
                latestDeck.getDescription(),
                latestDeck.getIconMediaId(),
                latestDeck.getTemplateId(),
                latestDeck.getTemplateVersion(),
                latestDeck.isPublicFlag(),
                latestDeck.isListed(),
                latestDeck.getLanguageCode(),
                latestDeck.getTags(),
                latestDeck.getCreatedAt(),
                now,
                now,
                latestDeck.getForkedFromDeck()
        );

        PublicDeckEntity savedNewDeck = publicDeckRepository.save(newDeckVersion);

        List<PublicCardEntity> clonedCards = new ArrayList<>(latestCards.size());
        for (PublicCardEntity old : latestCards) {
            String checksum = normalizeChecksum(old.getChecksum());
            if (checksum == null) {
                checksum = computeChecksum(old.getContent());
            }

            boolean active = old.isActive();
            if (matchByCardId && old.getCardId().equals(matchingCardId)) {
                active = false;
            } else if (!matchByCardId && targetChecksum != null) {
                String latestChecksum = normalizeChecksum(old.getChecksum());
                if (latestChecksum == null) {
                    latestChecksum = computeChecksum(old.getContent());
                }
                if (targetChecksum.equals(latestChecksum)) {
                    active = false;
                }
            }

            PublicCardEntity clone = new PublicCardEntity(
                    savedNewDeck.getDeckId(),
                    savedNewDeck.getVersion(),
                    savedNewDeck,
                    old.getContent(),
                    old.getOrderIndex(),
                    old.getTags(),
                    old.getCreatedAt(),
                    old.getUpdatedAt(),
                    active,
                    checksum
            );
            clonedCards.add(clone);
        }

        if (!clonedCards.isEmpty()) {
            publicCardRepository.saveAll(clonedCards);
        }

        userDeck.setCurrentVersion(savedNewDeck.getVersion());
        userDeck.setTemplateVersion(savedNewDeck.getTemplateVersion());
        userDeck.setLastSyncedAt(now);
        userDeckRepository.save(userDeck);

        userCardRepository.save(card);
    }

    // ==== Приватные мапперы и утилиты ==== //

    private UserCardDTO toUserCardDTO(UserCardEntity c) {
        PublicCardEntity publicCard = null;
        if (c.getPublicCardId() != null) {
            publicCard = publicCardRepository.findByCardId(c.getPublicCardId()).orElse(null);
        }

        JsonNode effective = buildEffectiveContent(c, publicCard);
        String[] tags = buildEffectiveTags(c, publicCard);

        return new UserCardDTO(
                c.getUserCardId(),
                c.getPublicCardId(),
                c.isCustom(),
                c.isDeleted(),
                c.getPersonalNote(),
                tags,
                effective
        );
    }

    private PublicCardDTO toPublicCardDTO(PublicCardEntity c) {
        return new PublicCardDTO(
                c.getDeckId(),
                c.getDeckVersion(),
                c.getCardId(),
                c.getContent(),
                c.getOrderIndex(),
                c.getTags(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.isActive(),
                c.getChecksum()
        );
    }

    // простое слияние: override перекрывает поля из public.content
    private JsonNode buildEffectiveContent(UserCardEntity c, PublicCardEntity publicCard) {
        JsonNode override = c.getContentOverride();

        // кастомная карта без привязки к public - просто override
        if (c.getPublicCardId() == null) {
            return override;
        }

        if (publicCard == null) {
            return override;
        }

        JsonNode base = publicCard.getContent();
        return mergeJson(base, override);
    }

    private String[] buildEffectiveTags(UserCardEntity c, PublicCardEntity publicCard) {
        if (c.getTags() != null) {
            return c.getTags();
        }

        if (publicCard == null) {
            return null;
        }

        return publicCard.getTags();
    }

    private boolean tagsEqual(String[] first, String[] second) {
        if (first == null && second == null) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.length != second.length) {
            return false;
        }
        for (int i = 0; i < first.length; i++) {
            if (first[i] == null && second[i] == null) {
                continue;
            }
            if (first[i] == null || second[i] == null) {
                return false;
            }
            if (!first[i].equals(second[i])) {
                return false;
            }
        }
        return true;
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

        // если это не объекты (например, строки/массивы) - считаем, что override важнее
        return override;
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
}

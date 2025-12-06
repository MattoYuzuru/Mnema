package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.entity.*;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CardService {

    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;
    private final PublicDeckRepository publicDeckRepository;
    private final FieldTemplateRepository fieldTemplateRepository;

    public CardService(UserDeckRepository userDeckRepository,
                       UserCardRepository userCardRepository,
                       PublicCardRepository publicCardRepository,
                       PublicDeckRepository publicDeckRepository,
                       FieldTemplateRepository fieldTemplateRepository) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
        this.publicDeckRepository = publicDeckRepository;
        this.fieldTemplateRepository = fieldTemplateRepository;
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
                .findByUserDeckIdAndDeletedFalseAndSuspendedFalseOrderByCreatedAtAsc(userDeckId, pageable)
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
                .findByDeckIdAndDeckVersionOrderByOrderIndex(deck.getDeckId(), deck.getVersion(), pageable)
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
                .findByTemplateIdOrderByOrderIndexAsc(templateId)
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

        List<UserCardDTO> result = new ArrayList<>();

        // Локальная колода без привязки к public_decks
        if (userDeck.getPublicDeckId() == null) {
            for (CreateCardRequest request : requests) {
                JsonNode content = request.contentOverride() != null
                        ? request.contentOverride()
                        : request.content();

                UserCardEntity userCard = new UserCardEntity(
                        currentUserId,
                        userDeckId,
                        null,
                        true,               // кастомная карта
                        false,
                        request.personalNote(),
                        content,
                        Instant.now(),
                        null,
                        null,
                        null,
                        0,
                        false
                );

                UserCardEntity saved = userCardRepository.save(userCard);
                result.add(toUserCardDTO(saved));
            }

            return result;
        }

        // Колода с привязкой к публичной
        UUID publicDeckId = userDeck.getPublicDeckId();

        // Берём последнюю версию публичной колоды
        PublicDeckEntity latestDeck = publicDeckRepository
                .findLatestByDeckId(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));

        // Если текущий юзер не автор – просто кастомные карты без публичной части
        if (!latestDeck.getAuthorId().equals(currentUserId)) {
            for (CreateCardRequest request : requests) {
                JsonNode content = request.contentOverride() != null
                        ? request.contentOverride()
                        : request.content();

                UserCardEntity userCard = new UserCardEntity(
                        currentUserId,
                        userDeckId,
                        null,
                        true,
                        false,
                        request.personalNote(),
                        content,
                        Instant.now(),
                        null,
                        null,
                        null,
                        0,
                        false
                );

                UserCardEntity saved = userCardRepository.save(userCard);
                result.add(toUserCardDTO(saved));
            }

            return result;
        }

        // Автор публичной колоды – создаём новую версию public_decks
        int newVersion = latestDeck.getVersion() + 1;
        Instant now = Instant.now();

        PublicDeckEntity newDeckVersion = new PublicDeckEntity(
                latestDeck.getDeckId(),     // тот же deck_id
                newVersion,
                latestDeck.getAuthorId(),
                latestDeck.getName(),
                latestDeck.getDescription(),
                latestDeck.getTemplateId(),
                latestDeck.isPublicFlag(),
                latestDeck.isListed(),
                latestDeck.getLanguageCode(),
                latestDeck.getTags(),
                now,
                now,
                now,
                latestDeck.getForkedFromDeck()
        );

        PublicDeckEntity savedNewDeck = publicDeckRepository.save(newDeckVersion);

        // Копируем карты из предыдущей версии в новую
        List<PublicCardEntity> oldCards = publicCardRepository
                .findByDeckIdAndDeckVersion(latestDeck.getDeckId(), latestDeck.getVersion());

        List<PublicCardEntity> clonedOldCards = new ArrayList<>();
        for (PublicCardEntity old : oldCards) {
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
                    old.getChecksum()
            );
            clonedOldCards.add(clone);
        }
        if (!clonedOldCards.isEmpty()) {
            publicCardRepository.saveAll(clonedOldCards);
        }

        // Добавляем НОВЫЕ публичные карты для новой версии

        int highestOrderIndex = oldCards.stream()
                .mapToInt(PublicCardEntity::getOrderIndex)
                .max()
                .orElse(oldCards.size() + 1);

        List<PublicCardEntity> newPublicCards = new ArrayList<>();
        for (CreateCardRequest request : requests) {
            PublicCardEntity pc = new PublicCardEntity(
                    savedNewDeck.getDeckId(),
                    savedNewDeck.getVersion(),
                    savedNewDeck,
                    request.content(),
                    request.orderIndex(),
                    request.tags(),
                    now,
                    null,
                    true,
                    request.checksum()
            );
            newPublicCards.add(pc);
        }

        List<PublicCardEntity> savedNewPublicCards =
                newPublicCards.isEmpty() ? List.of() : publicCardRepository.saveAll(newPublicCards);

        // Обновляем версию в юзер-колоде автора (остальные будут синкаться вручную)
        userDeck.setCurrentVersion(savedNewDeck.getVersion());
        userDeck.setLastSyncedAt(now);
        userDeckRepository.save(userDeck);

        // Создаём юзер-карты для новых публичных карт (старые юзер-карты остаются как есть)
        for (int i = 0; i < savedNewPublicCards.size(); i++) {
            PublicCardEntity publicCard = savedNewPublicCards.get(i);
            CreateCardRequest request = requests.get(i);

            UserCardEntity userCard = new UserCardEntity(
                    currentUserId,
                    userDeckId,
                    publicCard.getCardId(),
                    false,                 // не кастомная, а каноническая для автора
                    false,
                    request.personalNote(),
                    request.contentOverride(),
                    now,
                    null,
                    null,
                    null,
                    0,
                    false
            );

            UserCardEntity savedUserCard = userCardRepository.save(userCard);
            result.add(toUserCardDTO(savedUserCard));
        }

        return result;
    }

    // Обновление юзер карты (без SR логики)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserCardDTO updateUserCard(UUID currentUserId,
                                      UUID userDeckId,
                                      UUID userCardId,
                                      UserCardDTO dto) {

        UserCardEntity card = userCardRepository.findById(userCardId)
                .orElseThrow(() -> new IllegalArgumentException("User card not found: " + userCardId));

        if (!card.getUserDeckId().equals(userDeckId) || !card.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to card " + userCardId);
        }

        card.setPersonalNote(dto.personalNote());
        // Для простоты считаем, что effectiveContent, присланный с фронта, и есть новый override
        card.setContentOverride(dto.effectiveContent());
        card.setSuspended(dto.isSuspended());
        card.setDeleted(dto.isDeleted());
        card.setUpdatedAt(Instant.now());

        UserCardEntity saved = userCardRepository.save(card);
        return toUserCardDTO(saved);
    }

    // Логическое удаление юзер карты
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteUserCard(UUID currentUserId, UUID userDeckId, UUID userCardId) {
        UserCardEntity card = userCardRepository.findById(userCardId)
                .orElseThrow(() -> new IllegalArgumentException("User card not found: " + userCardId));

        if (!card.getUserDeckId().equals(userDeckId) || !card.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to card " + userCardId);
        }

        card.setDeleted(true);
        card.setUpdatedAt(Instant.now());
        userCardRepository.save(card);
    }

    // ==== Приватные мапперы и утилиты ==== //

    private UserCardDTO toUserCardDTO(UserCardEntity c) {
        JsonNode effective = buildEffectiveContent(c);

        return new UserCardDTO(
                c.getUserCardId(),
                c.getPublicCardId(),
                c.isCustom(),
                c.isDeleted(),
                c.isSuspended(),
                c.getPersonalNote(),
                effective,
                c.getLastReviewAt(),
                c.getNextReviewAt(),
                c.getReviewCount()
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
    private JsonNode buildEffectiveContent(UserCardEntity c) {
        JsonNode override = c.getContentOverride();

        // кастомная карта без привязки к public - просто override
        if (c.getPublicCardId() == null) {
            return override;
        }

        // пытаемся подтянуть public-карту
        var publicCardOpt = publicCardRepository.findByCardId(c.getPublicCardId());
        if (publicCardOpt.isEmpty()) {
            return override;
        }

        JsonNode base = publicCardOpt.get().getContent();
        return mergeJson(base, override);
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

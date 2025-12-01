package app.mnema.core.deck.service;


import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class CardService {

    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;
    private final PublicDeckRepository publicDeckRepository;

    public CardService(UserDeckRepository userDeckRepository, UserCardRepository userCardRepository, PublicCardRepository publicCardRepository, PublicDeckRepository publicDeckRepository) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
        this.publicDeckRepository = publicDeckRepository;
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

        Pageable pageable = PageRequest.of(page - 1, limit);

        return userCardRepository
                .findByUserDeckIdAndDeletedFalseAndSuspendedFalseOrderByCreatedAtAsc(userDeckId, pageable)
                .map(this::toUserCardDTO);

    }

    // Просмотр публичных карт колоды по deck_id + version
    @Transactional(readOnly = true)
    public Page<PublicCardDTO> getPublicCards(UUID deckId, Integer deckVersion, int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        // Определяем версию и проверяем, что колода публичная
        PublicDeckEntity deck;
        if (deckVersion == null) {
            deck = publicDeckRepository
                    .findTopByDeckIdOrderByVersionDesc(deckId)
                    .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + deckId));
        } else {
            deck = publicDeckRepository
                    .findByDeckIdAndVersion(deckId, deckVersion)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Public deck not found: deckId=" + deckId + ", version=" + deckVersion
                    ));
        }

        if (!deck.isPublicFlag()) {
            throw new SecurityException("Deck is not public: " + deckId);
        }

        return publicCardRepository
                .findByDeckIdAndDeckVersionOrderByOrderIndex(deck.getDeckId(), deck.getVersion(), pageable)
                .map(this::toPublicCardDTO);
    }

    // Добавление карты в пользовательскую колоду
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserCardDTO addNewCardToDeck(UUID currentUserId,
                                        UUID userDeckId,
                                        CreateCardRequest request) {

        UserDeckEntity userDeck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!userDeck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        PublicDeckEntity publicDeck = publicDeckRepository
                .findByDeckIdAndVersion(userDeck.getPublicDeckId(), userDeck.getCurrentVersion())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Public deck not found: deckId=" + userDeck.getPublicDeckId() +
                                ", version=" + userDeck.getCurrentVersion()
                ));

        if (publicDeck.getAuthorId().equals(currentUserId)) {
            // TODO: Я не особо обновляю версию, нужно как-то это решить

            PublicCardEntity publicCard = new PublicCardEntity(
                    publicDeck.getDeckId(),
                    publicDeck.getVersion(),
                    publicDeck,
                    request.content(),
                    request.orderIndex(),
                    request.tags(),
                    Instant.now(),
                    null,
                    true,
                    request.checksum()
            );

            PublicCardEntity savedPublicCard = publicCardRepository.save(publicCard);

            UserCardEntity userCard = new UserCardEntity(
                    currentUserId,
                    userDeckId,
                    savedPublicCard.getCardId(),
                    true,
                    false,
                    request.personalNote(),
                    request.contentOverride(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    0,
                    false
            );

            return toUserCardDTO(userCardRepository.save(userCard));

        } else {

            UserCardEntity userCard = new UserCardEntity(
                    currentUserId,
                    userDeckId,
                    null, // кастомная карта
                    true,
                    false,
                    request.personalNote(),
                    request.contentOverride(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    0,
                    false
            );

            return toUserCardDTO(userCardRepository.save(userCard));

        }
    }

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
            // setAll из другого ObjectNode
            merged.setAll(overrideObj);
            return merged;
        }

        // если это не объекты (например, строки/массивы) - считаем, что override важнее
        return override;
    }

}

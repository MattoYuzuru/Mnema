package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeckService {

    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;
    private final PublicDeckRepository publicDeckRepository;

    public DeckService(UserDeckRepository userDeckRepository,
                       UserCardRepository userCardRepository,
                       PublicCardRepository publicCardRepository, PublicDeckRepository publicDeckRepository) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
        this.publicDeckRepository = publicDeckRepository;
    }

    // Просмотр всех пользовательских колод постранично
    public Page<UserDeckDTO> getUserDecksByPage(UUID userId, int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return userDeckRepository
                .findByUserIdAndArchivedFalse(userId, pageable)
                .map(this::toUserDeckDTO);
    }

    // Просмотр всех карт в пользовательской колоде
    public Page<UserCardDTO> getUserCardsByDeck(UUID userDeckId, int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        return userCardRepository
                .findByUserDeck_UserDeckIdAndDeletedFalseAndSuspendedFalseOrderByCreatedAtAsc(userDeckId, pageable)
                .map(this::toUserCardDTO);
    }

    // Просмотр публичных карт колоды по deck_id + version
    public Page<PublicCardDTO> getPublicCards(UUID deckId, Integer deckVersion, int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return publicCardRepository
                .findByDeckIdAndDeckVersionOrderByOrderIndex(deckId, deckVersion, pageable)
                .map(this::toPublicCardDTO);
    }

    /*
    Новая колода всегда состоит из двух копий:
    Public-deck и User-deck

    Публичная часть будет открыта для форка
    (если юзер сделал её публичной)
    В случае открытия не нужно будет делать новую,
    просто меняем флаг

    Юзеры работают с юзер-колодой, с комментариями и тд
    Юзер колоды позволяют оставить версионирование пуб колод
    */
//    @Transactional
    public UserDeckDTO createNewDeck(UUID userId, PublicDeckDTO publicDeckDTO) {
        // Создаём и сохраняем публичную деку
        PublicDeckEntity publicDeckEntity = toPublicDeckEntityForCreate(userId, publicDeckDTO);
        PublicDeckEntity savedPublicDeck = publicDeckRepository.save(publicDeckEntity);

        // Создаём пустую юзер деку с дефолтами, ссылаемся на публичную
        UserDeckEntity userDeck = new UserDeckEntity(
                userId,
                savedPublicDeck.getDeckId(),
                savedPublicDeck.getVersion(),
                savedPublicDeck.getVersion(),
                true,
                SrAlgorithm.sm2.toString(),
                null,
                savedPublicDeck.getName(),
                savedPublicDeck.getDescription(),
                Instant.now(),
                null,
                false
        );

        UserDeckEntity savedUserDeck = userDeckRepository.save(userDeck);
        return toUserDeckDTO(savedUserDeck);
    }

    // Добавление карты в пользовательскую колоду
    public UserCardDTO addNewCardToDeck(UUID userId,
                                        UUID userDeckId,
                                        CreateCardRequest request) {

        UserDeckEntity userDeck = userDeckRepository.findByUserDeckId(userDeckId);
        if (userDeck == null) {
            throw new IllegalArgumentException("User deck not found: " + userDeckId);
        }

        PublicDeckEntity publicDeck = publicDeckRepository
                .findByDeckIdAndVersion(userDeck.getPublicDeckId(), userDeck.getCurrentVersion())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Public deck not found: deckId=" + userDeck.getPublicDeckId() +
                                ", version=" + userDeck.getCurrentVersion()
                ));

        if (publicDeck.getAuthorId().equals(userId)) {
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
                    userId,
                    userDeck,
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
                    userId,
                    userDeck,
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

    //  Utils and mappers

    private PublicDeckEntity toPublicDeckEntityForCreate(UUID authorId, PublicDeckDTO publicDeckDTO) {
        return new PublicDeckEntity(
                1,
                authorId,
                publicDeckDTO.name(),
                publicDeckDTO.description(),
                publicDeckDTO.templateId(),
                publicDeckDTO.isPublic(),
                publicDeckDTO.isListed(),
                publicDeckDTO.language(),
                publicDeckDTO.tags(),
                Instant.now(),
                null,
                null,
                publicDeckDTO.forkedFromDeck()
        );
    }

    private UserDeckDTO toUserDeckDTO(UserDeckEntity e) {
        return new UserDeckDTO(
                e.getUserDeckId(),
                e.getUserId(),
                e.getPublicDeckId(),
                e.getSubscribedVersion(),
                e.getCurrentVersion(),
                e.isAutoUpdate(),
                e.getAlgorithmId(),
                e.getAlgorithmParams(),
                e.getDisplayName(),
                e.getDisplayDescription(),
                e.getCreatedAt(),
                e.getLastSyncedAt(),
                e.isArchived()
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

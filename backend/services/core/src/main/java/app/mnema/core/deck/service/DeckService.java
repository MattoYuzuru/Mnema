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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class DeckService {

    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;
    private final PublicDeckRepository publicDeckRepository;

    public DeckService(UserDeckRepository userDeckRepository,
                       UserCardRepository userCardRepository,
                       PublicCardRepository publicCardRepository,
                       PublicDeckRepository publicDeckRepository) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
        this.publicDeckRepository = publicDeckRepository;
    }

    // Просмотр всех публичных колод
    public Page<PublicDeckDTO> getPublicDecksByPage(int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        return publicDeckRepository
                .findByPublicFlagTrueAndListedTrue(pageable)
                .map(this::toPublicDeckDTO);
    }

    // Просмотр всех пользовательских колод постранично
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('SCOPE_user.read')")
    public Page<UserDeckDTO> getUserDecksByPage(UUID currentUserId, int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return userDeckRepository
                .findByUserIdAndArchivedFalse(currentUserId, pageable)
                .map(this::toUserDeckDTO);
    }

    // Просмотр всех карт в пользовательской колоде
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('SCOPE_user.read')")
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
    @Transactional
    @PreAuthorize("hasAnyAuthority('SCOPE_user.write')")
    public UserDeckDTO createNewDeck(UUID currentUserId, PublicDeckDTO publicDeckDTO) {
        // Создаём и сохраняем публичную деку
        PublicDeckEntity publicDeckEntity = toPublicDeckEntityForCreate(currentUserId, publicDeckDTO);
        PublicDeckEntity savedPublicDeck = publicDeckRepository.save(publicDeckEntity);

        // Создаём пустую юзер деку с дефолтами, ссылаемся на публичную
        UserDeckEntity userDeck = new UserDeckEntity(
                currentUserId,
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
    @Transactional
    @PreAuthorize("hasAnyAuthority('SCOPE_user.write')")
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

    @Transactional
    @PreAuthorize("hasAnyAuthority('SCOPE_user.write')")
    public UserDeckDTO forkFromPublicDeck(UUID currentUserId, UUID publicDeckId) {

        // Проверка: уже есть форк / подписка
        var existing = userDeckRepository.findByUserIdAndPublicDeckId(currentUserId, publicDeckId);
        if (existing.isPresent()) {
            // можно просто вернуть существующую юзер-дека
            return toUserDeckDTO(existing.get());
        }

        // Находим последнюю версию колоды
        PublicDeckEntity publicDeck = publicDeckRepository
                .findTopByDeckIdOrderByVersionDesc(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));

        // Запрещаем форкать свою же колоду (или возвращаем уже существующую)
        // TODO: сделать новый метод для выдачи колод без колод залогиненого юзера
        if (publicDeck.getAuthorId().equals(currentUserId)) {
            throw new IllegalStateException("Author cannot fork own deck");
        }

        // Делаем пустую форкнутую юзер-колоду
        UserDeckDTO userDeckDTO = new UserDeckDTO(
                null,
                currentUserId,
                publicDeckId,
                publicDeck.getVersion(),
                publicDeck.getVersion(),
                true,
                SrAlgorithm.sm2.toString(),
                null,
                publicDeck.getName(),
                publicDeck.getDescription(),
                Instant.now(),
                Instant.now(),
                false
        );

        UserDeckEntity userDeckEntity = userDeckRepository.save(toUserDeckEntityForFork(userDeckDTO));

        // Заполняем юзер-колоду юзер-картами с маппингом публичных-карт
        List<PublicCardEntity> publicCardEntities = publicCardRepository
                .findByDeckIdAndDeckVersion(publicDeckId, publicDeck.getVersion());

        List<UserCardEntity> userCardEntities = new ArrayList<>();

        for (var publicCard : publicCardEntities) {
            UserCardEntity userCard = new UserCardEntity(
                    currentUserId,                         // владелец — тот, кто форкает
                    userDeckEntity.getUserDeckId(), // subscription_id -> user_decks.user_deck_id
                    publicCard.getCardId(),
                    false,
                    false,
                    null,
                    null,
                    Instant.now(),
                    null,
                    null,
                    null,
                    0,
                    false
            );

            userCardEntities.add(userCard);
        }

        userCardRepository.saveAll(userCardEntities);

        return toUserDeckDTO(userDeckEntity);
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

    private UserDeckEntity toUserDeckEntityForFork(UserDeckDTO userDeckDTO) {
        return new UserDeckEntity(
                userDeckDTO.userId(),
                userDeckDTO.publicDeckId(),
                userDeckDTO.subscribedVersion(),
                userDeckDTO.currentVersion(),
                userDeckDTO.autoUpdate(),
                userDeckDTO.algorithmId(),
                userDeckDTO.algorithmParams(),
                userDeckDTO.displayName(),
                userDeckDTO.displayDescription(),
                userDeckDTO.createdAt(),
                userDeckDTO.lastSyncedAt(),
                userDeckDTO.archived()
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

    private PublicDeckDTO toPublicDeckDTO(PublicDeckEntity e) {
        return new PublicDeckDTO(
                e.getDeckId(),
                e.getVersion(),
                e.getAuthorId(),
                e.getName(),
                e.getDescription(),
                e.getTemplateId(),
                e.isPublicFlag(),
                e.isListed(),
                e.getLanguageCode(),
                e.getTags(),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                e.getPublishedAt(),
                e.getForkedFromDeck()
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

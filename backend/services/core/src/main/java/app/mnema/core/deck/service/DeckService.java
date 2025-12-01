package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
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
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<UserDeckDTO> getUserDecksByPage(UUID currentUserId, int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return userDeckRepository
                .findByUserIdAndArchivedFalse(currentUserId, pageable)
                .map(this::toUserDeckDTO);
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
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
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

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
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
}

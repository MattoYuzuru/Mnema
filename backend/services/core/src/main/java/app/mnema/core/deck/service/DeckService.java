package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.DeckSizeDTO;
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
import java.util.Objects;
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

    // Публичный каталог: только последние версии каждой публичной колоды
    @Transactional(readOnly = true)
    public Page<PublicDeckDTO> getPublicDecksByPage(int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        return publicDeckRepository
                .findLatestPublicVisibleDecks(pageable)
                .map(this::toPublicDeckDTO);
    }

    // Получить публичную колоду по deckId и опционально по версии
    @Transactional(readOnly = true)
    public PublicDeckDTO getPublicDeck(UUID deckId, Integer version) {
        PublicDeckEntity deck;
        if (version == null) {
            deck = publicDeckRepository
                    .findLatestByDeckId(deckId)
                    .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + deckId));
        } else {
            deck = publicDeckRepository
                    .findByDeckIdAndVersion(deckId, version)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Public deck not found: deckId=" + deckId + ", version=" + version
                    ));
        }
        return toPublicDeckDTO(deck);
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

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public DeckSizeDTO getUserDeckSize(UUID currentUserId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        long cardsCount = userCardRepository.countByUserDeckIdAndDeletedFalseAndSuspendedFalse(userDeckId);

        return new DeckSizeDTO(userDeckId, cardsCount);
    }

    @Transactional(readOnly = true)
    public DeckSizeDTO getPublicDeckSize(UUID deckId, Integer version) {
        PublicDeckEntity deck;

        if (version == null) {
            deck = publicDeckRepository
                    .findLatestByDeckId(deckId)
                    .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + deckId));
        } else {
            deck = publicDeckRepository
                    .findByDeckIdAndVersion(deckId, version)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Public deck not found: deckId=" + deckId + ", version=" + version
                    ));
        }

        long cardsCount = publicCardRepository.countByDeckIdAndDeckVersion(deck.getDeckId(), deck.getVersion());

        return new DeckSizeDTO(deck.getDeckId(), cardsCount);
    }


    // Получить одну пользовательскую колоду
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public UserDeckDTO getUserDeck(UUID currentUserId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        return toUserDeckDTO(deck);
    }

    // Создать новую колоду: создаём public_decks v1 и user_decks
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserDeckDTO createNewDeck(UUID currentUserId, PublicDeckDTO publicDeckDTO) {

        PublicDeckEntity publicDeckEntity = toPublicDeckEntityForCreate(currentUserId, publicDeckDTO);
        PublicDeckEntity savedPublicDeck = publicDeckRepository.save(publicDeckEntity);

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

    // Обновление мета-информации пользовательской колоды
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserDeckDTO updateUserDeckMeta(UUID currentUserId, UUID userDeckId, UserDeckDTO dto) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        if (dto.displayName() != null) {
            deck.setDisplayName(dto.displayName());
        }
        if (dto.displayDescription() != null) {
            deck.setDisplayDescription(dto.displayDescription());
        }
        deck.setAutoUpdate(dto.autoUpdate());

        if (dto.algorithmId() != null) {
            deck.setAlgorithmId(dto.algorithmId());
        }
        if (dto.algorithmParams() != null) {
            deck.setAlgorithmParams(dto.algorithmParams());
        }

        deck.setArchived(dto.archived());
        deck.setLastSyncedAt(Instant.now());

        UserDeckEntity saved = userDeckRepository.save(deck);
        return toUserDeckDTO(saved);
    }

    // Архивирование (логическое удаление) пользовательской колоды
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteUserDeck(UUID currentUserId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        deck.setArchived(true);
        deck.setLastSyncedAt(Instant.now());
        userDeckRepository.save(deck);
    }

    // Ручной синк юзер-колоды на последнюю версию публичной колоды
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserDeckDTO syncUserDeckToLatestVersion(UUID currentUserId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        if (deck.getPublicDeckId() == null) {
            throw new IllegalStateException("Local deck has no public source");
        }

        UserDeckEntity finalDeck = deck;
        PublicDeckEntity latest = publicDeckRepository
                .findLatestByDeckId(deck.getPublicDeckId())
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + finalDeck.getPublicDeckId()));

        if (!Objects.equals(deck.getCurrentVersion(), latest.getVersion())) {
            deck.setCurrentVersion(latest.getVersion());
            deck.setLastSyncedAt(Instant.now());
            deck = userDeckRepository.save(deck);
        }

        // Карты и SR не трогаем – это следующая итерация
        return toUserDeckDTO(deck);
    }

    // Обновление мета-информации публичной колоды (без изменения версии)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public PublicDeckDTO updatePublicDeckMeta(UUID currentUserId,
                                              UUID deckId,
                                              Integer version,
                                              PublicDeckDTO dto) {

        PublicDeckEntity deck;
        if (version == null) {
            deck = publicDeckRepository
                    .findLatestByDeckId(deckId)
                    .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + deckId));
        } else {
            deck = publicDeckRepository
                    .findByDeckIdAndVersion(deckId, version)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Public deck not found: deckId=" + deckId + ", version=" + version
                    ));
        }

        if (!deck.getAuthorId().equals(currentUserId)) {
            throw new SecurityException("Only author can update deck meta");
        }

        if (dto.name() != null) {
            deck.setName(dto.name());
        }
        if (dto.description() != null) {
            deck.setDescription(dto.description());
        }
        deck.setPublicFlag(dto.isPublic());
        deck.setListed(dto.isListed());

        if (dto.language() != null) {
            deck.setLanguageCode(dto.language());
        }
        if (dto.tags() != null) {
            deck.setTags(dto.tags());
        }

        deck.setUpdatedAt(Instant.now());

        PublicDeckEntity saved = publicDeckRepository.save(deck);
        return toPublicDeckDTO(saved);
    }

    // Форк публичной колоды
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserDeckDTO forkFromPublicDeck(UUID currentUserId, UUID publicDeckId) {

        var existing = userDeckRepository.findByUserIdAndPublicDeckId(currentUserId, publicDeckId);
        if (existing.isPresent()) {
            return toUserDeckDTO(existing.get());
        }

        PublicDeckEntity publicDeck = publicDeckRepository
                .findLatestByDeckId(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));

        if (publicDeck.getAuthorId().equals(currentUserId)) {
            throw new IllegalStateException("Author cannot fork own deck");
        }

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

        List<PublicCardEntity> publicCardEntities = publicCardRepository
                .findByDeckIdAndDeckVersion(publicDeckId, publicDeck.getVersion());

        List<UserCardEntity> userCardEntities = new ArrayList<>();

        for (PublicCardEntity publicCard : publicCardEntities) {
            UserCardEntity userCard = new UserCardEntity(
                    currentUserId,
                    userDeckEntity.getUserDeckId(),
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

        if (!userCardEntities.isEmpty()) {
            userCardRepository.saveAll(userCardEntities);
        }

        return toUserDeckDTO(userDeckEntity);
    }

    private PublicDeckEntity toPublicDeckEntityForCreate(UUID authorId, PublicDeckDTO publicDeckDTO) {
        Instant now = Instant.now();
        return new PublicDeckEntity(
                UUID.randomUUID(),
                1,
                authorId,
                publicDeckDTO.name(),
                publicDeckDTO.description(),
                publicDeckDTO.templateId(),
                publicDeckDTO.isPublic(),
                publicDeckDTO.isListed(),
                publicDeckDTO.language(),
                publicDeckDTO.tags(),
                now,
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

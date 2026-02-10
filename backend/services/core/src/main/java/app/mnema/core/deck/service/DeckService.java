package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.DeckSizeDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import app.mnema.core.media.service.MediaResolveCache;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class DeckService {

    private static final int MAX_DECK_NAME = 50;
    private static final int MAX_DECK_DESCRIPTION = 200;
    private static final int MAX_TAGS = 5;
    private static final int MAX_TAG_LENGTH = 25;

    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;
    private final PublicDeckRepository publicDeckRepository;
    private final CardTemplateRepository cardTemplateRepository;
    private final MediaResolveCache mediaResolveCache;

    public DeckService(UserDeckRepository userDeckRepository,
                       UserCardRepository userCardRepository,
                       PublicCardRepository publicCardRepository,
                       PublicDeckRepository publicDeckRepository,
                       CardTemplateRepository cardTemplateRepository,
                       MediaResolveCache mediaResolveCache) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
        this.publicDeckRepository = publicDeckRepository;
        this.cardTemplateRepository = cardTemplateRepository;
        this.mediaResolveCache = mediaResolveCache;
    }

    // Публичный каталог: только последние версии каждой публичной колоды
    @Transactional(readOnly = true)
    public Page<PublicDeckDTO> getPublicDecksByPage(int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<PublicDeckEntity> decksPage = publicDeckRepository.findLatestPublicVisibleDecks(pageable);
        Map<UUID, String> iconUrls = resolveIconUrls(decksPage.getContent());

        return decksPage.map(deck -> toPublicDeckDTO(deck, resolveIconUrl(iconUrls, deck.getIconMediaId())));
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
        String iconUrl = resolveIconUrl(deck.getIconMediaId());
        return toPublicDeckDTO(deck, iconUrl);
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
    public List<UUID> getUserPublicDeckIds(UUID currentUserId) {
        return userDeckRepository.findPublicDeckIdsByUserId(currentUserId);
    }

    // Просмотр архивированных/удаленных колод
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('SCOPE_user.read')")
    public List<UserDeckDTO> getDeletedUserDecks(UUID currentUserId) {
        return userDeckRepository
                .findByUserIdAndArchivedTrue(currentUserId)
                .stream()
                .map(this::toUserDeckDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public DeckSizeDTO getUserDeckSize(UUID currentUserId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        long cardsCount = userCardRepository.countByUserDeckIdAndDeletedFalse(userDeckId);

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

        long cardsCount = publicCardRepository.countByDeckIdAndDeckVersionAndActiveTrue(
                deck.getDeckId(),
                deck.getVersion()
        );

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
        validateDeckMeta(publicDeckDTO.name(), publicDeckDTO.description(), publicDeckDTO.tags());

        int templateVersion = resolveTemplateVersion(publicDeckDTO.templateId(), publicDeckDTO.templateVersion());
        PublicDeckEntity publicDeckEntity = toPublicDeckEntityForCreate(currentUserId, publicDeckDTO, templateVersion);
        PublicDeckEntity savedPublicDeck = publicDeckRepository.save(publicDeckEntity);

        UserDeckEntity userDeck = new UserDeckEntity(
                currentUserId,
                savedPublicDeck.getDeckId(),
                savedPublicDeck.getVersion(),
                savedPublicDeck.getVersion(),
                savedPublicDeck.getTemplateVersion(),
                savedPublicDeck.getTemplateVersion(),
                true,
                SrAlgorithm.fsrs_v6.toString(),
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
        for (int attempt = 0; attempt < 3; attempt++) {
            UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                    .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

            if (!deck.getUserId().equals(currentUserId)) {
                throw new SecurityException("Access denied to deck " + userDeckId);
            }

            if (dto.displayName() != null) {
                validateLength(dto.displayName(), MAX_DECK_NAME, "Display name");
                deck.setDisplayName(dto.displayName());
            }
            if (dto.displayDescription() != null) {
                validateLength(dto.displayDescription(), MAX_DECK_DESCRIPTION, "Display description");
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

            try {
                UserDeckEntity saved = userDeckRepository.save(deck);
                return toUserDeckDTO(saved);
            } catch (OptimisticLockingFailureException ex) {
                if (attempt == 2) {
                    throw ex;
                }
                Thread.yield();
            }
        }
        throw new IllegalStateException("Failed to update deck meta: " + userDeckId);
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

    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void hardDeleteUserDeck(UUID currentUserId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        if (!deck.isArchived()) {
            throw new IllegalStateException("Deck must be archived before hard delete");
        }

        UUID publicDeckId = deck.getPublicDeckId();
        boolean canDeletePublic = false;

        if (publicDeckId != null) {
            var latestPublic = publicDeckRepository.findLatestByDeckId(publicDeckId).orElse(null);
            if (latestPublic != null && latestPublic.getAuthorId().equals(currentUserId)) {
                long others = userDeckRepository.countByPublicDeckIdAndUserDeckIdNot(publicDeckId, userDeckId);
                canDeletePublic = others == 0;
            }
        }

        userDeckRepository.delete(deck);

        if (canDeletePublic) {
            publicDeckRepository.deleteByDeckId(publicDeckId);
        }
    }

    // Архивирование публичной колоды
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deletePublicDeck(UUID currentUserId, UUID publicDeckId) {
        PublicDeckEntity deck = publicDeckRepository.findLatestByDeckId(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));

        if (!deck.getAuthorId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + publicDeckId);
        }

        deck.setListed(false);
        deck.setPublicFlag(false);
        deck.setUpdatedAt(Instant.now());
        publicDeckRepository.save(deck);
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

    /*
      Документация sync (как и почему это работает)

      Модель данных:
      - public_decks хранит immutable версии колоды.
      - public_cards хранит snapshot карт внутри каждой версии.
      - card_id в public_cards генерируется заново при клонировании в новую версию и глобально уникален.
      - user_cards ссылаются на public_cards через public_card_id = card_id и не знают deck_version.

      Проблема:
      - При выходе новой версии те же логические карты получают новые card_id.
      - Если при sync просто "добавить карты последней версии", будут дубли: старые user_cards останутся, новые добавятся.

      Решение:
      - Используем public_cards.checksum как стабильный идентификатор "логической карты" между версиями.
      - При sync делаем две фазы:
        1) Rewire: если checksum user-старой public-card совпадает с checksum публичной карты в latest версии,
           обновляем user_cards.public_card_id на card_id из latest версии.
           Так сохраняется SR прогресс и локальные override, так как user_card_id остаётся прежним.
        2) Add missing: добавляем новые user_cards только для тех checksum latest версии, которых у пользователя ещё не было.
           Это предотвращает рост user_cards при каждом обновлении.

      Ограничение:
      - checksum должен быть заполнен и детерминирован (на сервере).
      - Внутри одной версии checksum должен быть уникальным, иначе маппинг неоднозначен.
        При обнаружении дублей checksum мы не делаем rewire для таких checksum (fail-safe).
     */

        UUID publicDeckId = deck.getPublicDeckId();

        PublicDeckEntity latestDeck = publicDeckRepository
                .findLatestByDeckId(publicDeckId)
                .orElseThrow(() -> new IllegalArgumentException("Public deck not found: " + publicDeckId));

        int latestVersion = latestDeck.getVersion();
        Integer latestTemplateVersion = latestDeck.getTemplateVersion();
        Instant now = Instant.now();

        // Если уже на последней версии, просто обновляем last_synced_at
        if (Objects.equals(deck.getCurrentVersion(), latestVersion)) {
            if (!Objects.equals(deck.getTemplateVersion(), latestTemplateVersion)) {
                deck.setTemplateVersion(latestTemplateVersion);
            }
            deck.setLastSyncedAt(now);
            return toUserDeckDTO(userDeckRepository.save(deck));
        }

        // 1) Последние публичные карты (берём только active)
        List<PublicCardEntity> latestPublicCardsAll =
                publicCardRepository.findByDeckIdAndDeckVersion(publicDeckId, latestVersion);

        List<PublicCardEntity> latestPublicCards = new ArrayList<>();
        for (PublicCardEntity pc : latestPublicCardsAll) {
            if (pc.isActive()) {
                latestPublicCards.add(pc);
            }
        }

        // 2) Все user-cards колоды (включая deleted/suspended), чтобы не "воскрешать" скрытые карты
        List<UserCardEntity> userCards = userCardRepository.findByUserDeckId(userDeckId);

        // 3) Собираем publicCardId из user-cards и подтягиваем соответствующие public-cards (чтобы получить их checksum)
        List<UUID> linkedPublicCardIds = new ArrayList<>();
        for (UserCardEntity uc : userCards) {
            if (uc.getPublicCardId() != null) {
                linkedPublicCardIds.add(uc.getPublicCardId());
            }
        }

        List<PublicCardEntity> linkedPublicCards = linkedPublicCardIds.isEmpty()
                ? List.of()
                : publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(linkedPublicCardIds);

        Map<UUID, PublicCardEntity> linkedByCardId = new HashMap<>();
        for (PublicCardEntity pc : linkedPublicCards) {
            linkedByCardId.putIfAbsent(pc.getCardId(), pc);
        }

        // checksum -> publicCard для latest версии (только уникальные checksum)
        Map<String, PublicCardEntity> latestByChecksum = new HashMap<>();
        Set<String> duplicateChecksums = new HashSet<>();

        for (PublicCardEntity pc : latestPublicCards) {
            String chk = normalizeChecksum(pc.getChecksum());
            if (chk == null) {
                continue;
            }
            PublicCardEntity prev = latestByChecksum.putIfAbsent(chk, pc);
            if (prev != null) {
                duplicateChecksums.add(chk);
            }
        }
        for (String dup : duplicateChecksums) {
            latestByChecksum.remove(dup);
        }

        // Все public_card_id, которые уже есть у пользователя
        Set<UUID> existingLinkedPublicCardIds = new HashSet<>(linkedPublicCardIds);

        // checksum, которые уже присутствуют у пользователя (включая deleted)
        Set<String> knownChecksums = new HashSet<>();

        // 4) Rewire
        List<UserCardEntity> toUpdate = new ArrayList<>();
        for (UserCardEntity uc : userCards) {
            UUID oldPublicCardId = uc.getPublicCardId();
            if (oldPublicCardId == null) {
                continue;
            }

            PublicCardEntity oldPublic = linkedByCardId.get(oldPublicCardId);
            if (oldPublic == null) {
                continue;
            }

            if (uc.getTags() == null && oldPublic.getTags() != null) {
                uc.setTags(oldPublic.getTags());
            }

            String chk = normalizeChecksum(oldPublic.getChecksum());
            if (chk == null) {
                continue;
            }

            knownChecksums.add(chk);

            if (duplicateChecksums.contains(chk)) {
                continue;
            }

            PublicCardEntity latestPublic = latestByChecksum.get(chk);
            if (latestPublic == null) {
                continue;
            }

            UUID newPublicCardId = latestPublic.getCardId();
            if (newPublicCardId.equals(oldPublicCardId)) {
                continue;
            }

            if (existingLinkedPublicCardIds.contains(newPublicCardId)) {
                continue;
            }

            uc.setPublicCardId(newPublicCardId);
            uc.setUpdatedAt(now);

            existingLinkedPublicCardIds.remove(oldPublicCardId);
            existingLinkedPublicCardIds.add(newPublicCardId);

            toUpdate.add(uc);
        }

        if (!toUpdate.isEmpty()) {
            userCardRepository.saveAll(toUpdate);
        }

        // 5) Add missing (только активные latest карты)
        List<UserCardEntity> toInsert = new ArrayList<>();
        for (PublicCardEntity pc : latestPublicCards) {
            String chk = normalizeChecksum(pc.getChecksum());

            if (chk != null && knownChecksums.contains(chk)) {
                continue;
            }

            UUID cardId = pc.getCardId();
            if (existingLinkedPublicCardIds.contains(cardId)) {
                continue;
            }

            UserCardEntity newUserCard = new UserCardEntity(
                    currentUserId,
                    userDeckId,
                    cardId,
                    false,
                    false,
                    null,
                    null,
                    null,
                    now,
                    null
            );

            toInsert.add(newUserCard);
            existingLinkedPublicCardIds.add(cardId);
        }

        if (!toInsert.isEmpty()) {
            userCardRepository.saveAll(toInsert);
        }

        // 6) Обновляем только current_version, subscribed_version не трогаем
        deck.setCurrentVersion(latestVersion);
        deck.setTemplateVersion(latestTemplateVersion);
        deck.setLastSyncedAt(now);

        deck = userDeckRepository.save(deck);
        return toUserDeckDTO(deck);
    }

    // Ручной синк шаблона пользовательской колоды на последнюю версию шаблона
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public UserDeckDTO syncUserDeckTemplate(UUID currentUserId, UUID userDeckId) {
        UserDeckEntity deck = userDeckRepository.findById(userDeckId)
                .orElseThrow(() -> new IllegalArgumentException("User deck not found: " + userDeckId));

        if (!deck.getUserId().equals(currentUserId)) {
            throw new SecurityException("Access denied to deck " + userDeckId);
        }

        if (deck.getPublicDeckId() == null) {
            throw new IllegalStateException("Local deck has no public source");
        }

        Integer currentDeckVersion = deck.getCurrentVersion();
        if (currentDeckVersion == null) {
            throw new IllegalStateException("Deck version is not set for " + userDeckId);
        }

        PublicDeckEntity publicDeck = publicDeckRepository
                .findByDeckIdAndVersion(deck.getPublicDeckId(), currentDeckVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Public deck not found: deckId=" + deck.getPublicDeckId() + ", version=" + currentDeckVersion
                ));

        UUID templateId = publicDeck.getTemplateId();
        Integer latestTemplateVersion = resolveTemplateVersion(templateId, null);
        deck.setTemplateVersion(latestTemplateVersion);
        deck.setLastSyncedAt(Instant.now());
        return toUserDeckDTO(userDeckRepository.save(deck));
    }

    private String normalizeChecksum(String checksum) {
        if (checksum == null) {
            return null;
        }
        String s = checksum.trim();
        return s.isEmpty() ? null : s;
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
            validateLength(dto.name(), MAX_DECK_NAME, "Deck name");
            deck.setName(dto.name());
        }
        if (dto.description() != null) {
            validateLength(dto.description(), MAX_DECK_DESCRIPTION, "Deck description");
            deck.setDescription(dto.description());
        }
        if (dto.iconMediaId() != null) {
            deck.setIconMediaId(dto.iconMediaId());
        }
        deck.setPublicFlag(dto.isPublic());
        deck.setListed(dto.isListed());

        if (dto.language() != null) {
            deck.setLanguageCode(dto.language());
        }
        if (dto.tags() != null) {
            validateTags(dto.tags());
            deck.setTags(dto.tags());
        }

        deck.setUpdatedAt(Instant.now());

        PublicDeckEntity saved = publicDeckRepository.save(deck);
        return toPublicDeckDTO(saved, null);
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

        Instant now = Instant.now();

        UserDeckDTO userDeckDTO = new UserDeckDTO(
                null,
                currentUserId,
                publicDeckId,
                publicDeck.getVersion(),
                publicDeck.getVersion(),
                publicDeck.getTemplateVersion(),
                publicDeck.getTemplateVersion(),
                true,
                SrAlgorithm.fsrs_v6.toString(),
                null,
                publicDeck.getName(),
                publicDeck.getDescription(),
                now,
                now,
                false
        );

        UserDeckEntity userDeckEntity = userDeckRepository.save(toUserDeckEntityForFork(userDeckDTO));

        List<PublicCardEntity> publicCardEntities = publicCardRepository
                .findByDeckIdAndDeckVersion(publicDeckId, publicDeck.getVersion());

        List<UserCardEntity> userCardEntities = new ArrayList<>();

        for (PublicCardEntity publicCard : publicCardEntities) {
            if (!publicCard.isActive()) {
                continue;
            }

            UserCardEntity userCard = new UserCardEntity(
                    currentUserId,
                    userDeckEntity.getUserDeckId(),
                    publicCard.getCardId(),
                    false,
                    false,
                    null,
                    null,
                    null,
                    now,
                    null
            );
            userCardEntities.add(userCard);
        }

        if (!userCardEntities.isEmpty()) {
            userCardRepository.saveAll(userCardEntities);
        }

        return toUserDeckDTO(userDeckEntity);
    }

    private PublicDeckEntity toPublicDeckEntityForCreate(UUID authorId,
                                                         PublicDeckDTO publicDeckDTO,
                                                         int templateVersion) {
        Instant now = Instant.now();
        return new PublicDeckEntity(
                UUID.randomUUID(),
                1,
                authorId,
                publicDeckDTO.name(),
                publicDeckDTO.description(),
                publicDeckDTO.iconMediaId(),
                publicDeckDTO.templateId(),
                templateVersion,
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

    private void validateDeckMeta(String name, String description, String[] tags) {
        validateLength(name, MAX_DECK_NAME, "Deck name");
        validateLength(description, MAX_DECK_DESCRIPTION, "Deck description");
        validateTags(tags);
    }

    private void validateTags(String[] tags) {
        if (tags == null) {
            return;
        }
        if (tags.length > MAX_TAGS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many tags");
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag is too long");
            }
        }
    }

    private void validateLength(String value, int maxLength, String label) {
        if (value == null) {
            return;
        }
        if (value.length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + " must be at most " + maxLength + " characters"
            );
        }
    }

    private int resolveTemplateVersion(UUID templateId, Integer templateVersion) {
        if (templateVersion != null) {
            return templateVersion;
        }
        if (templateId == null) {
            return 1;
        }
        Optional<CardTemplateEntity> templateOpt = cardTemplateRepository.findById(templateId);
        if (templateOpt == null || templateOpt.isEmpty()) {
            return 1;
        }
        Integer latestVersion = templateOpt.get().getLatestVersion();
        return latestVersion != null ? latestVersion : 1;
    }

    private UserDeckEntity toUserDeckEntityForFork(UserDeckDTO userDeckDTO) {
        return new UserDeckEntity(
                userDeckDTO.userId(),
                userDeckDTO.publicDeckId(),
                userDeckDTO.subscribedVersion(),
                userDeckDTO.currentVersion(),
                userDeckDTO.templateVersion(),
                userDeckDTO.subscribedTemplateVersion(),
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
                e.getTemplateVersion(),
                e.getSubscribedTemplateVersion(),
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

    private PublicDeckDTO toPublicDeckDTO(PublicDeckEntity e, String iconUrl) {
        return new PublicDeckDTO(
                e.getDeckId(),
                e.getVersion(),
                e.getAuthorId(),
                e.getName(),
                e.getDescription(),
                e.getIconMediaId(),
                iconUrl,
                e.getTemplateId(),
                e.getTemplateVersion(),
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

    private Map<UUID, String> resolveIconUrls(List<PublicDeckEntity> decks) {
        if (decks == null || decks.isEmpty()) {
            return Map.of();
        }

        List<UUID> mediaIds = decks.stream()
                .map(PublicDeckEntity::getIconMediaId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (mediaIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> urls = new HashMap<>();
        mediaResolveCache.resolve(mediaIds)
                .forEach((id, media) -> urls.put(id, media.url()));
        return urls;
    }

    private String resolveIconUrl(UUID iconMediaId) {
        if (iconMediaId == null) {
            return null;
        }
        var resolved = mediaResolveCache.resolve(List.of(iconMediaId)).get(iconMediaId);
        return resolved == null ? null : resolved.url();
    }

    private String resolveIconUrl(Map<UUID, String> iconUrls, UUID iconMediaId) {
        if (iconMediaId == null || iconUrls == null || iconUrls.isEmpty()) {
            return null;
        }
        return iconUrls.get(iconMediaId);
    }
}

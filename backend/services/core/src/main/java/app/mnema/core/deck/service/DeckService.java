package app.mnema.core.deck.service;


import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DeckService {
    private final UserDeckRepository userDeckRepository;
    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;

    public DeckService(UserDeckRepository userDeckRepository, UserCardRepository userCardRepository, PublicCardRepository publicCardRepository) {
        this.userDeckRepository = userDeckRepository;
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
    }

    public Page<UserDeckDTO> getAllUserDecksByPage(int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        return userDeckRepository.findAll(pageable).map(this::toUserDeckDTO);
    }

    public Page<UserCardDTO> getUserCardsByDeckIdAndByUserId(int page, int limit, UUID deckId) {
        Pageable pageable = PageRequest.of(page - 1, limit);

        UserDeckDTO userDeckDTO = toUserDeckDTO(userDeckRepository.findByUserDeckId(deckId));

        var publicDeckIdId = userDeckDTO.publicDeckId();
        var currentVersion = userDeckDTO.currentVersion();
        List<PublicCardEntity> publicCardEntityList = publicCardRepository.findByDeckIdAndDeckVersionOrderByOrderIndex(publicDeckIdId, currentVersion);

        return publicCardEntityList.stream().map(this::toPublicCardDTO).toList();
    }

    private UserDeckDTO toUserDeckDTO(UserDeckEntity userDeckEntity) {

        return new UserDeckDTO(
                userDeckEntity.getUserDeckId(),
                userDeckEntity.getUserId(),
                userDeckEntity.getPublicDeckId(),
                userDeckEntity.getSubscribedVersion(),
                userDeckEntity.getCurrentVersion(),
                userDeckEntity.isAutoUpdate(),
                userDeckEntity.getAlgorithmId(),
                userDeckEntity.getAlgorithmParams(),
                userDeckEntity.getDisplayName(),
                userDeckEntity.getDisplayDescription(),
                userDeckEntity.getCreatedAt(),
                userDeckEntity.getLastSyncedAt(),
                userDeckEntity.isArchived()
        );
    }

    private PublicCardDTO toPublicCardDTO(PublicCardEntity publicCardEntity) {
        return new PublicCardDTO(
            publicCardEntity.getDeckVersion(),
            publicCardEntity.getCardId(),
            publicCardEntity.getDeck(),
            publicCardEntity.getContent(),
            publicCardEntity.getOrderIndex(),
            publicCardEntity.getTags(),
            publicCardEntity.getCreatedAt(),
            publicCardEntity.getUpdatedAt(),
            publicCardEntity.isActive(),
            publicCardEntity.getChecksum()
        );
    }
}

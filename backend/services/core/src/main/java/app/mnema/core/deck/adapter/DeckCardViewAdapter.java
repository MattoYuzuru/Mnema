package app.mnema.core.deck.adapter;

import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.review.api.CardViewPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DeckCardViewAdapter implements CardViewPort {

    private final UserCardRepository userCardRepository;
    private final PublicCardRepository publicCardRepository;

    public DeckCardViewAdapter(UserCardRepository userCardRepository,
                               PublicCardRepository publicCardRepository) {
        this.userCardRepository = userCardRepository;
        this.publicCardRepository = publicCardRepository;
    }

    @Override
    public List<CardView> getCardViews(UUID userId, List<UUID> userCardIds) {
        if (userCardIds == null || userCardIds.isEmpty()) {
            return List.of();
        }

        List<UserCardEntity> cards = userCardRepository.findAllById(userCardIds);

        Map<UUID, UserCardEntity> byId = cards.stream()
                .collect(Collectors.toMap(UserCardEntity::getUserCardId, Function.identity()));

        // Валидация: все карточки существуют и принадлежат юзеру
        for (UUID id : userCardIds) {
            UserCardEntity uc = byId.get(id);
            if (uc == null) {
                throw new IllegalArgumentException("User card not found: " + id);
            }
            if (!userId.equals(uc.getUserId())) {
                throw new SecurityException("Access denied to card " + id);
            }
            if (uc.isDeleted()) {
                throw new IllegalStateException("Card is deleted: " + id);
            }
        }

        // Батч-загрузка публичных карточек
        Set<UUID> publicCardIds = cards.stream()
                .map(UserCardEntity::getPublicCardId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, PublicCardEntity> publicById = publicCardIds.isEmpty()
                ? Map.of()
                : resolveLatestPublicCards(publicCardIds);

        // Собираем результат в исходном порядке userCardIds
        List<CardView> result = new ArrayList<>(userCardIds.size());
        for (UUID userCardId : userCardIds) {
            UserCardEntity uc = byId.get(userCardId);

            JsonNode effective;
            UUID publicCardId = uc.getPublicCardId();

            if (publicCardId == null) {
                // Кастомная карта: contentOverride у тебя фактически и есть контент
                effective = uc.getContentOverride();
            } else {
                PublicCardEntity pc = publicById.get(publicCardId);
                if (pc == null) {
                    throw new IllegalStateException("Public card not found: " + publicCardId);
                }
                effective = merge(pc.getContent(), uc.getContentOverride());
            }

            result.add(new CardView(
                    uc.getUserCardId(),
                    uc.getPublicCardId(),
                    uc.isCustom(),
                    effective
            ));
        }

        return result;
    }

    private JsonNode merge(JsonNode base, JsonNode override) {
        if (override == null || override.isNull()) return base;
        if (base == null || base.isNull()) return override;
        if (!base.isObject() || !override.isObject()) return override;

        ObjectNode out = base.deepCopy();
        override.properties().forEach(e -> out.set(e.getKey(), e.getValue()));
        return out;
    }

    private Map<UUID, PublicCardEntity> resolveLatestPublicCards(Set<UUID> publicCardIds) {
        List<PublicCardEntity> cards = publicCardRepository.findAllByCardIdInOrderByDeckVersionDesc(publicCardIds);
        Map<UUID, PublicCardEntity> map = new HashMap<>();
        for (PublicCardEntity card : cards) {
            map.putIfAbsent(card.getCardId(), card);
        }
        return map;
    }
}

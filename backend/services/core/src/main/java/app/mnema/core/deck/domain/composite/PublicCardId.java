package app.mnema.core.deck.domain.composite;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class PublicCardId implements Serializable {
    private UUID deckId;
    private Integer deckVersion;
    private UUID cardId;

    public PublicCardId() {
    }

    public PublicCardId(UUID deckId, Integer deckVersion, UUID cardId) {
        this.deckId = deckId;
        this.deckVersion = deckVersion;
        this.cardId = cardId;
    }

    public UUID getDeckId() {
        return deckId;
    }

    public void setDeckId(UUID deckId) {
        this.deckId = deckId;
    }

    public Integer getDeckVersion() {
        return deckVersion;
    }

    public void setDeckVersion(Integer deckVersion) {
        this.deckVersion = deckVersion;
    }

    public UUID getCardId() {
        return cardId;
    }

    public void setCardId(UUID cardId) {
        this.cardId = cardId;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PublicCardId that = (PublicCardId) o;
        return Objects.equals(deckId, that.deckId) && Objects.equals(deckVersion, that.deckVersion) && Objects.equals(cardId, that.cardId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deckId, deckVersion, cardId);
    }
}

package app.mnema.core.deck.domain.composite;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class DeckUpdateSessionId implements Serializable {
    private UUID deckId;
    private UUID operationId;

    public DeckUpdateSessionId() {}

    public DeckUpdateSessionId(UUID deckId, UUID operationId) {
        this.deckId = deckId;
        this.operationId = operationId;
    }

    public UUID getDeckId() {
        return deckId;
    }

    public void setDeckId(UUID deckId) {
        this.deckId = deckId;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public void setOperationId(UUID operationId) {
        this.operationId = operationId;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        DeckUpdateSessionId that = (DeckUpdateSessionId) o;
        return Objects.equals(deckId, that.deckId) && Objects.equals(operationId, that.operationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deckId, operationId);
    }
}

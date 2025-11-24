package app.mnema.core.deck.domain.composite;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class PublicDeckId implements Serializable {
    private UUID deckId;
    private Integer version;

    public PublicDeckId() {}

    public PublicDeckId(UUID deckId, Integer version) {
        this.deckId = deckId;
        this.version = version;
    }

    public UUID getDeckId() {
        return deckId;
    }

    public void setDeckId(UUID deckId) {
        this.deckId = deckId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PublicDeckId that = (PublicDeckId) o;
        return Objects.equals(deckId, that.deckId) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deckId, version);
    }
}

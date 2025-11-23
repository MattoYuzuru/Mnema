package app.mnema.core.deck.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

@Entity
public class Deck {
    @Id
    public UUID id;
}

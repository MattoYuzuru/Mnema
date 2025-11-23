package app.mnema.core.deck.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

@Entity
public class Card {
    @Id
    public UUID id;
}

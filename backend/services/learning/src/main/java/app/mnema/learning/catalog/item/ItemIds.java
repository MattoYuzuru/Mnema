package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;

import java.util.UUID;

final class ItemIds {
    private ItemIds() { }

    static UUID entity(String value) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "id");
            if (!id.toString().equalsIgnoreCase(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }
}

package app.mnema.core.review.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public interface CardViewPort {

    record CardView(
            UUID userCardId,
            UUID publicCardId,
            boolean isCustom,
            JsonNode effectiveContent
    ) {}

    List<CardView> getCardViews(UUID userId, List<UUID> userCardIds);
}

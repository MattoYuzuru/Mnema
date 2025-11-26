package app.mnema.core.deck.domain.request;


import com.fasterxml.jackson.databind.JsonNode;

public record CreateCardRequest(
        JsonNode content, // базовый контент
        Integer orderIndex,
        String[] tags,
        String personalNote,
        JsonNode contentOverride, // частичный override
        String checksum
) {
}

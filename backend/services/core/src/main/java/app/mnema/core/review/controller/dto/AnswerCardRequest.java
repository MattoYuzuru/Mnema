package app.mnema.core.review.controller.dto;

import app.mnema.core.review.domain.ReviewSource;
import com.fasterxml.jackson.databind.JsonNode;

public record AnswerCardRequest(
        String rating,
        Integer responseMs,
        ReviewSource source,
        JsonNode features
) {}

package app.mnema.core.review.controller.dto;

import app.mnema.core.review.domain.ReviewSource;

public record AnswerCardRequest(
        String rating,
        Integer responseMs,
        ReviewSource source
) {}

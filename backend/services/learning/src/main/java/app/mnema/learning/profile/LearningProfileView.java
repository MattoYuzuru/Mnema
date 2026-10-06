package app.mnema.learning.profile;

/**
 * The body of {@code GET/PUT /api/learning-profile}. Unanswered: {@code goal} and {@code answeredAt} are null. A skip has
 * {@code skipped: true}, a null goal and an {@code answeredAt}.
 */
public record LearningProfileView(String goal, boolean skipped, String answeredAt) { }

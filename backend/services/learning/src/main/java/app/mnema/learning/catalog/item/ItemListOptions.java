package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;

/**
 * Validated {@code sort} and {@code include} query parameters of the item list. Both are closed vocabularies; unknown or
 * duplicated tokens are rejected rather than ignored so a client typo is never silently a different order.
 */
record ItemListOptions(Sort sort, boolean exerciseCount) {
    enum Sort { ORDINAL, EXERCISE_COUNT }

    static final ItemListOptions DEFAULT = new ItemListOptions(Sort.ORDINAL, false);

    static ItemListOptions parse(String sort, String include) {
        Sort order = sort == null ? Sort.ORDINAL : switch (sort) {
            case "ordinal" -> Sort.ORDINAL;
            case "exerciseCount" -> Sort.EXERCISE_COUNT;
            default -> throw new InvalidRequestException();
        };
        boolean counts = order == Sort.EXERCISE_COUNT;
        if (include != null) {
            String[] tokens = include.split(",", -1);
            if (tokens.length != 1 || !tokens[0].equals("exerciseCount")) throw new InvalidRequestException();
            counts = true;
        }
        return new ItemListOptions(order, counts);
    }
}

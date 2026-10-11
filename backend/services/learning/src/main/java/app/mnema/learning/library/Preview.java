package app.mnema.learning.library;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a link preview (Open Graph card) may show, all plain text: the public fields of a deck or an author profile, or the generic Mnema card. Every case
 * that is not a public deck or a profile with a public card is {@link Kind#GENERIC} with exactly the same fields and values, so the answer says nothing about
 * why a code is not shown (private, link-only, invitation-only, rotated, deleted, unknown or never published). {@code canonical} is absent for the generic
 * card. The wire contract is {@code contracts/decks/public-meta.json}.
 */
record Preview(Kind kind, String title, String description, String canonical, String robots, String image, Author author) {
    enum Kind { DECK, PROFILE, GENERIC }

    /** The author card fields Identity published with the owner's consent; {@code displayName} is absent unless the owner agreed to show it. */
    record Author(String username, String displayName) {
        /** How the author is written in a sentence: the name with the login, or the login alone. */
        String label() { return displayName == null ? "@" + username : displayName + " (@" + username + ")"; }
    }

    static final String GENERIC_TITLE = "Mnema — учиться, понимать, помнить";
    static final String GENERIC_DESCRIPTION = "Ваши материалы и упражнения — в одном месте.";
    static final String IMAGE_PATH = "/assets/og-image.png";

    static Preview generic(String origin) {
        return new Preview(Kind.GENERIC, GENERIC_TITLE, GENERIC_DESCRIPTION, null, "noindex", origin + IMAGE_PATH, null);
    }

    ObjectNode toJson() {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("kind", kind.name()).put("title", title).put("description", description);
        if (canonical == null) result.putNull("canonical");
        else result.put("canonical", canonical);
        result.put("robots", robots).put("image", image);
        if (author == null) {
            result.putNull("author");
        } else {
            ObjectNode card = result.putObject("author").put("username", author.username());
            if (author.displayName() == null) card.putNull("displayName");
            else card.put("displayName", author.displayName());
        }
        return result;
    }
}

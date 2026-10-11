package app.mnema.learning.library;

/**
 * The command must publish the deck's current head: leaving «Приватная» without a published revision, or becoming «Публичная» while the published revision is not the head
 * (the media check judges the head, readers get the published revision). {@code 400 PUBLICATION_REQUIRED}, nothing is written.
 */
public final class PublicationRequiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PublicationRequiredException() { super("The deck must publish its current head", null, false, false); }
}

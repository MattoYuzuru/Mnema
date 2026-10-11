package app.mnema.learning.platform.api;

/**
 * The content exists but a legal takedown names this revision (Share/15, #437): HTTP 410. It extends {@link ResourceNotFoundException} on purpose, so
 * every internal caller that handles an unreadable material (AI context, pinned sources) treats it as absent, while the HTTP boundary can still tell the
 * client that the content was removed.
 */
public final class ContentUnavailableException extends ResourceNotFoundException {
    private static final long serialVersionUID = 1L;
}

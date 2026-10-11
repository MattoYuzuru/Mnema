package app.mnema.learning.library;

import app.mnema.learning.platform.security.AuthorCards;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Link previews without SSR (Share/14, architecture section 9, CD-10): what the web server puts into {@code index.html} for {@code /d/{code}/{slug}} so
 * Telegram, VK and search engines see the deck, not the empty application shell. It is a guest's view of the deck, never a signed-in one: the answer is
 * the same for everybody, so it may be cached by anyone for a few minutes.
 *
 * <ul>
 *   <li>A PUBLIC deck with a published revision gives its title, description (about 200 characters, on a word boundary), the canonical address
 *       {@code {origin}/d/{code}/{slug}}, {@code index,follow} and, when Identity has the author's public card, the author.</li>
 *   <li>Everything else (a link-only or invitation-only deck, a private one, a rotated, deleted, unknown or malformed code, a deck without a published
 *       revision) gives <b>the same generic card</b>, {@code noindex}, no canonical and no slug, so the answer is not an oracle for anything
 *       {@link DeckAccess} hides. The costs differ by one Identity call only for PUBLIC decks, which are public anyway.</li>
 *   <li>A profile gives a card only when Identity publishes one for the login (the owner's consent); otherwise the generic card.</li>
 * </ul>
 * The kill switch, the abuse limit and the bulkhead are the ones of the public reads ({@link PublicReadAdmission}); the bulkhead place is held for the
 * database lookup only, never across the Identity call. Outcomes are counted as {@code mnema_public_deck_requests_total{route="meta"}}: {@code ok} (a
 * public card), {@code generic}, and the admission's {@code disabled}, {@code rate_limited} and {@code busy}.
 */
@Service
public class PublicMetaService {
    private static final String ROUTE = "meta";
    private static final int MAX_TITLE = 120;
    private static final int MAX_DESCRIPTION = 200;
    private static final String DECK_DESCRIPTION_FALLBACK = "Колода на Mnema.";
    private static final String DECK_TITLE_FALLBACK = "Колода";
    private static final String PROFILE_DESCRIPTION_FALLBACK = "Профиль автора на Mnema.";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,50}");
    static final String DEFAULT_ORIGIN = "https://mnema.app";

    /** The preview of a deck and, for a public one, its canonical path ({@code /d/{code}/{slug}}); the path is absent for the generic card. */
    record DeckMeta(Preview preview, String canonicalPath) { }

    private record Draft(String title, String description, UUID ownerId, String slug) { }

    private final PublicReadAdmission admission;
    private final DeckAccess access;
    private final AuthorCards authors;
    private final TransactionTemplate transaction;
    private final String origin;

    PublicMetaService(PublicReadAdmission admission, DeckAccess access, AuthorCards authors, PlatformTransactionManager transactions,
                      @Value("${learning.community.public-origin:}") String origin) {
        this.admission = admission;
        this.access = access;
        this.authors = authors;
        this.origin = origin(origin);
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
        this.transaction.setReadOnly(true);
    }

    /** The preview behind a deck code. Never fails for a code that is merely not public; fails only for the admission (disabled, limited, busy). */
    DeckMeta deck(Viewer viewer, String code) {
        Draft draft = admission.within(ROUTE, viewer, () -> {
            Draft found = transaction.execute(status -> {
                Resolution resolution = access.resolve(viewer, code);
                // Only the public level is shown: the viewer is a guest, and LINK, INVITE and PRIVATE decks must look like nothing.
                if (!resolution.granted() || resolution.level() != AccessLevel.PUBLIC || resolution.deck().published() == null) return null;
                DeckRef.Published published = resolution.deck().published();
                return new Draft(published.title(), published.description(), resolution.deck().ownerId(), DeckSlug.of(published.title()));
            });
            admission.count(ROUTE, found == null ? "generic" : "ok");
            return found;
        });
        if (draft == null) return new DeckMeta(Preview.generic(origin), null);
        // After the place is released: a slow Identity must not hold a bulkhead place that deck reads need.
        Preview.Author author = authors.byAccount(draft.ownerId()).map(PublicMetaService::author).orElse(null);
        String path = "/d/" + code + "/" + draft.slug();
        String title = PreviewText.limit(PreviewText.plain(draft.title()), MAX_TITLE);
        String description = PreviewText.limit(PreviewText.plain(draft.description()), MAX_DESCRIPTION);
        Preview preview = new Preview(Preview.Kind.DECK, title.isEmpty() ? DECK_TITLE_FALLBACK : title,
                description.isEmpty() ? DECK_DESCRIPTION_FALLBACK : description, origin + path, "index,follow", origin + Preview.IMAGE_PATH, author);
        return new DeckMeta(preview, path);
    }

    /** The preview behind a login: the author's card when Identity has one, otherwise the generic card. */
    Preview profile(Viewer viewer, String username) {
        // Admission first, like a deck: the kill switch and the limit apply to every lookup, found or not.
        boolean admitted = admission.within(ROUTE, viewer, () -> true);
        if (!admitted || !USERNAME.matcher(username).matches()) return generic();
        return authors.byUsername(username).map(card -> {
            admission.count(ROUTE, "ok");
            Preview.Author author = author(card);
            String name = author.displayName() == null ? "@" + author.username() : author.displayName();
            String bio = PreviewText.limit(PreviewText.plain(card.bio()), MAX_DESCRIPTION);
            return new Preview(Preview.Kind.PROFILE, PreviewText.limit(PreviewText.plain(name), MAX_TITLE),
                    bio.isEmpty() ? PROFILE_DESCRIPTION_FALLBACK : bio, origin + "/u/" + card.username(), "index,follow",
                    origin + Preview.IMAGE_PATH, author);
        }).orElseGet(() -> {
            admission.count(ROUTE, "generic");
            return generic();
        });
    }

    /** The author as the preview may show it: the texts are made plain once more here, whatever the Identity client did, and a blank name is absent. */
    private static Preview.Author author(AuthorCards.Card card) {
        String name = PreviewText.limit(PreviewText.plain(card.displayName()), MAX_TITLE);
        return new Preview.Author(card.username(), name.isEmpty() ? null : name);
    }

    Preview generic() { return Preview.generic(origin); }

    /**
     * The public origin of the site (scheme and host, no path): HTTPS, or HTTP on a loopback host for local development. Blank is the production origin.
     * Anything else fails at startup, because every canonical address and image of every preview is built on it.
     */
    static String origin(String configured) {
        if (configured == null || configured.isBlank()) return DEFAULT_ORIGIN;
        URI uri;
        try {
            uri = URI.create(configured.strip());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid public origin");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!(scheme.equals("https") || (scheme.equals("http") && loopback)) || host.isEmpty() || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalArgumentException("Invalid public origin");
        }
        return scheme + "://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }
}

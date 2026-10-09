package app.mnema.learning.speech;

import app.mnema.learning.ai.prompt.Redactor;
import app.mnema.learning.catalog.content.ItemPreviews;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Recognition hints of a clip: the titles of the deck's current materials, cleaned into at most {@code learning.speech.max-hints} short terms. The deck
 * is the learner's own (checked at admission); a title is the learner's own text, so a term that looks like personal data (an e-mail address, a telephone
 * or card number as the prompt layer's {@link Redactor} sees them, a link, a long number) is dropped rather than sent to a provider. The learner's name, the deck's name and the owner id are never hints.
 */
@Component
class SpeechHints {
    static final int MAX_TERM = 64;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern PERSONAL = Pattern.compile("@|://|\\bwww\\.|\\d{5,}|\\+\\d");
    /** A bare address: {@code host.tld/path} or a host on a common top-level domain (not {@code .net}, which {@code ASP.NET} is); ordinary dotted names ({@code Node.js}) are not links. */
    private static final Pattern LINK = Pattern.compile(
            "(?iu)[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:[\\p{L}]{2,24}/|(?:com|ru|org|io|ai|app|dev|рф|su|me|info|co|uk|de|edu|gov)(?![\\p{L}\\p{N}]))");

    /** How many titles one call may have to read from storage (the rest is cached by then: a title is read once per material revision). */
    private static final int MAX_READS = 20;

    private final SpeechInputRepository repository;
    private final ItemPreviews previews;
    private final SpeechInputSettings settings;

    SpeechHints(SpeechInputRepository repository, ItemPreviews previews, SpeechInputSettings settings) {
        this.repository = repository;
        this.previews = previews;
        this.settings = settings;
    }

    /** The hints of {@code deck} (empty without one or when it is not the owner's). A title nobody has shown yet is read once and cached. */
    List<String> of(UUID owner, UUID deck) {
        if (deck == null || settings.maxHints() == 0) return List.of();
        List<String> titles = new ArrayList<>();
        int reads = 0;
        for (SpeechInputRepository.Head head : repository.deckHeads(owner, deck, settings.maxHints() * 3)) {
            String title = head.title();
            if (title == null && reads < MAX_READS) {
                reads++;
                try {
                    title = previews.title(deck, head.member(), head.revision(), head.scope(), head.root());
                } catch (RuntimeException unreadable) {
                    // a hint is a nicety: a material that cannot be read now is simply not one
                    title = null;
                }
            }
            if (title != null) titles.add(title);
        }
        return clean(titles, settings.maxHints());
    }

    /** Trims, collapses whitespace, drops control characters, over-long terms, personal-looking terms and repeats; keeps the first {@code max}. */
    static List<String> clean(List<String> titles, int max) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String title : titles) {
            if (title == null) continue;
            String term = SPACES.matcher(CONTROL.matcher(Normalizer.normalize(title, Normalizer.Form.NFC)).replaceAll(" ")).replaceAll(" ").strip();
            if (term.isEmpty() || term.length() > MAX_TERM || PERSONAL.matcher(term).find() || LINK.matcher(term).find()
                    || !Redactor.redact(term).equals(term)) {
                continue;
            }
            if (!seen.add(term.toLowerCase(Locale.ROOT))) continue;
            out.add(term);
            if (out.size() >= max) break;
        }
        return List.copyOf(out);
    }
}

package app.mnema.learning.library;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A deterministic, dependency-free guess of the language of a deck's text (title, description and some material titles) for the publication form
 * ("язык — определяется автоматически, можно поправить"). It answers a lowercase BCP 47 primary subtag, or empty when it is not sure; a wrong
 * confident answer is worse than none because the form pre-fills it.
 *
 * <p>Approach, in this order:
 * <ol>
 *   <li><b>Script.</b> Letters are counted per script; a script must hold at least {@value #DOMINANT_PERCENT} percent of all letters (a mixed text,
 *       such as a Russian description around English words, is decided by what dominates, not by its rarest words). Kana makes {@code ja} (also for
 *       Han mixed with at least a tenth of kana), Han alone {@code zh}, Hangul {@code ko}, Greek {@code el}, Hebrew {@code he}, Thai {@code th}.
 *       Scripts shared by several languages and not separable by the tables below (Arabic, Devanagari) are not guessed.</li>
 *   <li><b>Cyrillic.</b> Letters that exist in one language only decide {@code uk} ({@code іїєґ}), {@code be} ({@code ў}); letters of Serbian and
 *       Macedonian ({@code ђћљњџѓќѕ}) give no answer; otherwise {@code ru}, the default of the Russian-language product for a Cyrillic text.</li>
 *   <li><b>Latin.</b> Words are matched against small stopword lists of {@code en es de fr it pt}; a word that belongs to several lists weighs
 *       1/n for each of them, and a letter unique to one language ({@code ñ ¿ ¡ ß ã õ œ}) weighs one ({@code ä ö ü} half a point, Turkish and Hungarian have them
 *       too); a letter of an uncovered language ({@code ı ğ ş ł ą ę č ř å ø} and the like) leaves the text unanswered. The best language wins only with at least
 *       {@value #MIN_SCORE} points and a lead of one and a half times over the second; a short title of two content words stays unanswered.</li>
 * </ol>
 */
public final class LanguageDetector {
    static final int DOMINANT_PERCENT = 60;
    static final double MIN_SCORE = 2.0;
    private static final double LEAD = 1.5;

    private static final Map<String, List<String>> STOPWORDS = Map.of(
            "en", List.of("the", "and", "of", "to", "in", "is", "for", "with", "on", "that", "this", "are", "you", "your", "how", "what", "from", "by",
                    "an", "be", "as", "at", "or", "it", "not", "have", "has", "do", "does", "we", "can", "will", "all", "about", "into", "my", "their"),
            "es", List.of("el", "la", "los", "las", "de", "del", "y", "en", "que", "un", "una", "por", "para", "con", "es", "se", "su", "sus", "no",
                    "al", "lo", "como", "mas", "pero", "muy", "esta", "son", "este", "nos", "ser"),
            "fr", List.of("le", "la", "les", "des", "du", "de", "et", "en", "un", "une", "est", "que", "pour", "dans", "pas", "sur", "avec", "au",
                    "aux", "ce", "cette", "qui", "sont", "vous", "nous", "je", "il", "elle", "ne", "ses"),
            "de", List.of("der", "die", "das", "und", "ist", "ein", "eine", "nicht", "mit", "fur", "auf", "den", "dem", "des", "zu", "von", "im", "ich",
                    "sie", "es", "auch", "wie", "sich", "bei", "nach", "aus", "als", "oder", "wir", "sind"),
            "it", List.of("il", "lo", "la", "gli", "le", "di", "del", "della", "e", "che", "un", "una", "per", "con", "non", "sono", "in", "da", "piu",
                    "come", "anche", "questo", "nel", "nella", "al", "dei", "delle", "uno"),
            "pt", List.of("o", "os", "as", "de", "do", "da", "dos", "das", "e", "que", "um", "uma", "para", "com", "nao", "em", "no", "na", "por",
                    "mais", "como", "se", "sao", "foi", "ao", "ou", "seu", "sua"));
    private static final Map<String, Map<String, Double>> WEIGHT = new HashMap<>();
    /** Letters that point at one of the six languages: a full point for the sure ones, half a point for the umlauts that Turkish, Swedish and Hungarian share. */
    private static final Map<Integer, String> UNIQUE_LETTER = Map.ofEntries(
            Map.entry((int) 'ñ', "es"), Map.entry((int) '¿', "es"), Map.entry((int) '¡', "es"), Map.entry((int) 'ß', "de"), Map.entry((int) 'ã', "pt"),
            Map.entry((int) 'õ', "pt"), Map.entry((int) 'œ', "fr"));
    private static final String UMLAUTS = "äöü";
    /** Letters of languages the tables do not cover (Turkish, Polish, Czech, Hungarian, Scandinavian...): their presence makes the text unsure. */
    private static final String FOREIGN_LETTERS = "ığşłąęśźżćńčřěšžőűåøæ";

    static {
        Map<String, Integer> lists = new HashMap<>();
        STOPWORDS.forEach((language, words) -> words.forEach(word -> lists.merge(word, 1, Integer::sum)));
        STOPWORDS.forEach((language, words) -> {
            Map<String, Double> weights = new HashMap<>();
            words.forEach(word -> weights.put(word, 1.0 / lists.get(word)));
            WEIGHT.put(language, weights);
        });
    }

    private LanguageDetector() { }

    /** The language of the text, or empty when the text does not decide it. */
    public static Optional<String> detect(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        Map<Character.UnicodeScript, Integer> letters = new HashMap<>();
        int total = 0;
        boolean ukrainian = false;
        boolean belarusian = false;
        boolean southSlavic = false;
        for (int index = 0; index < text.length(); ) {
            int point = text.codePointAt(index);
            index += Character.charCount(point);
            if (!Character.isLetter(point)) continue;
            total++;
            letters.merge(Character.UnicodeScript.of(point), 1, Integer::sum);
            switch (Character.toLowerCase(point)) {
                case 'і', 'ї', 'є', 'ґ' -> ukrainian = true;
                case 'ў' -> belarusian = true;
                case 'ђ', 'ћ', 'љ', 'њ', 'џ', 'ѓ', 'ќ', 'ѕ' -> southSlavic = true;
                default -> { }
            }
        }
        if (total == 0) return Optional.empty();
        int kana = letters.getOrDefault(Character.UnicodeScript.HIRAGANA, 0) + letters.getOrDefault(Character.UnicodeScript.KATAKANA, 0);
        int han = letters.getOrDefault(Character.UnicodeScript.HAN, 0);
        if (kana > 0 && percent(kana + han, total) >= DOMINANT_PERCENT && kana * 10 >= kana + han) return Optional.of("ja");
        if (han > 0 && percent(han + kana, total) >= DOMINANT_PERCENT) return Optional.of(kana * 10 >= kana + han ? "ja" : "zh");
        Character.UnicodeScript top = letters.entrySet().stream().max(Map.Entry.<Character.UnicodeScript, Integer>comparingByValue()
                .thenComparing(entry -> entry.getKey().name())).orElseThrow().getKey();
        if (percent(letters.get(top), total) < DOMINANT_PERCENT) return Optional.empty();
        return switch (top) {
            case HANGUL -> Optional.of("ko");
            case GREEK -> Optional.of("el");
            case HEBREW -> Optional.of("he");
            case THAI -> Optional.of("th");
            case CYRILLIC -> southSlavic ? Optional.empty() : Optional.of(belarusian ? "be" : ukrainian ? "uk" : "ru");
            case LATIN -> latin(text);
            default -> Optional.empty();
        };
    }

    private static int percent(int part, int total) { return part * 100 / total; }

    private static Optional<String> latin(String text) {
        Map<String, Double> score = new HashMap<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int index = 0; index < lower.length(); ) {
            int point = lower.codePointAt(index);
            index += Character.charCount(point);
            if (FOREIGN_LETTERS.indexOf(point) >= 0) return Optional.empty();
            String unique = UNIQUE_LETTER.get(point);
            if (unique != null) score.merge(unique, 1.0, Double::sum);
            else if (UMLAUTS.indexOf(point) >= 0) score.merge("de", 0.5, Double::sum);
        }
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        for (String word : plain.split("[^\\p{L}]+")) {
            if (word.isEmpty()) continue;
            WEIGHT.forEach((language, weights) -> {
                Double weight = weights.get(word);
                if (weight != null) score.merge(language, weight, Double::sum);
            });
        }
        var ranked = score.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList();
        if (ranked.isEmpty() || ranked.getFirst().getValue() < MIN_SCORE) return Optional.empty();
        if (ranked.size() > 1 && ranked.getFirst().getValue() < LEAD * ranked.get(1).getValue()) return Optional.empty();
        return Optional.of(ranked.getFirst().getKey());
    }
}

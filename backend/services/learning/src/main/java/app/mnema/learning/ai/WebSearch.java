package app.mnema.learning.ai;

import java.net.IDN;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Web search port of the research step (AI-18, #299): the queries of one material go in, numbered-ready results come out. A result is a
 * pointer, never a page: a url, a title, a snippet of at most 300 characters, a date and where it came from; page text is not fetched or kept.
 *
 * <p>Implementations never throw for provider problems; a provider that fails is not a failure of the search while another one answers or
 * while some of the queries were answered. No method here logs or returns a key, a query or a provider message.
 */
public interface WebSearch {
    /** One snippet is cut to this many characters (the terms of the providers allow a quotation, not a copy). */
    int MAX_SNIPPET = 300;
    int MAX_TITLE = 300;
    /** A result URL longer than this is not a link we show. */
    int MAX_URL = 2_048;
    /** A query is bounded before it is sent: Yandex accepts 400 characters and 40 words. */
    int MAX_QUERY_CHARACTERS = 400;
    int MAX_QUERY_WORDS = 40;
    int MAX_QUERIES = 15;

    /**
     * Asks queries in order, one provider request each (Yandex) or in batches of up to five (Perplexity). The query count is also the maximum
     * number of billed requests, including paid answers rejected by the parser; an exhausted cap or budget returns the partial answer.
     *
     * @return the results ordered by query, then by rank; {@code Failed} only when no request was paid
     */
    AiResult<Answer> search(Request request);

    /** Whether at least one provider can be called now (a key present, the Stub selected). */
    default boolean configured() { return true; }

    /**
     * @param queries one to {@link #MAX_QUERIES} queries, each stripped, whitespace-collapsed and bounded
     * @param lang the language of the queries (ISO 639-1); a provider derives its index from it
     * @param maxResults results asked per query, 1..20
     * @param region an ISO 3166 country for providers that take one, or null
     * @param stepId the generation step for the call journal, null outside a step
     * @param deadline no further provider request starts at or after this instant (the search then returns what it has), null for none
     */
    record Request(List<String> queries, String lang, int maxResults, String region, UUID stepId, int attempt, Instant deadline) {
        public Request(List<String> queries, String lang, int maxResults, String region, UUID stepId, int attempt) {
            this(queries, lang, maxResults, region, stepId, attempt, null);
        }

        public Request {
            if (queries == null || queries.isEmpty() || queries.size() > MAX_QUERIES) throw new IllegalArgumentException("Invalid queries");
            // every outgoing query is redacted (e-mails, cards, phones) and bounded; one that is empty after that is dropped, never sent
            queries = queries.stream().map(Request::clean).filter(query -> !query.isEmpty()).toList();
            if (queries.isEmpty()) throw new IllegalArgumentException("Every query is blank");
            lang = lang == null || !lang.matches("[A-Za-z]{2}") ? "en" : lang.toLowerCase(Locale.ROOT);
            if (maxResults < 1 || maxResults > 20) throw new IllegalArgumentException("Invalid maxResults");
            region = region == null || !region.matches("[A-Za-z]{2}") ? null : region.toUpperCase(Locale.ROOT);
            attempt = Math.max(1, attempt);
        }

        /** The query as it is sent: personal-data patterns redacted, then {@link #bound}; empty when nothing is left. */
        public static String clean(String query) {
            return bound(app.mnema.learning.ai.prompt.Redactor.redact(query == null ? "" : query));
        }

        /** Whitespace collapsed, at most 40 words and 400 characters (a first word longer than that leaves nothing). */
        public static String bound(String query) {
            String[] words = (query == null ? "" : query).strip().split("\\s+");
            StringBuilder out = new StringBuilder();
            for (int index = 0; index < words.length && index < MAX_QUERY_WORDS; index++) {
                if (words[index].isEmpty()) continue;
                int next = out.length() + (out.isEmpty() ? 0 : 1) + words[index].length();
                if (next > MAX_QUERY_CHARACTERS) break;
                if (!out.isEmpty()) out.append(' ');
                out.append(words[index]);
            }
            return out.toString();
        }
    }

    /** The providers a result can come from; the name is the wire value of {@code research.results[].provider}. */
    enum Provider { YANDEX, PERPLEXITY, STUB }

    /**
     * One result. {@code queryIndex} is the 0-based position of the query in the request, {@code rank} the 1-based position in that query's
     * answer. {@code date} is an ISO date or null.
     */
    record Result(String url, String title, String snippet, String date, Provider provider, int queryIndex, int rank) {
        Result withQueryIndex(int index) { return new Result(url, title, snippet, date, provider, index, rank); }
    }

    /**
     * What the search produced.
     *
     * @param results ordered by query, then rank
     * @param requests the paid provider requests that were answered (the debit is per request); a request that failed is not counted
     * @param costMicros the provider cost of those requests in micro-US-dollars
     */
    record Answer(List<Result> results, int requests, long costMicros) {
        public Answer {
            results = List.copyOf(results);
        }
    }

    /**
     * The URL normalized to an ASCII link target, or null when it is not an acceptable one: absolute https, a host (an internationalized one is
     * converted with {@link IDN#toASCII}), no user info, non-ASCII characters of the path and query percent-encoded (UTF-8), the fragment dropped,
     * at most {@link #MAX_URL} printable ASCII characters and no whitespace or markup characters. The native-v1 {@code href} profile is checked by the
     * caller that numbers the results (it lives in the generation module).
     */
    static String acceptable(String value) {
        if (value == null) return null;
        String text = value.strip();
        if (text.isEmpty() || text.length() > 4 * MAX_URL || text.chars().anyMatch(c -> c <= ' ' || c == '<' || c == '>' || c == '"' || c == 0x7f)) return null;
        if (!text.regionMatches(true, 0, "https://", 0, 8)) return null;
        int fragment = text.indexOf('#');
        if (fragment >= 0) text = text.substring(0, fragment);
        String rest = text.substring(8);
        int end = 0;
        while (end < rest.length() && "/?".indexOf(rest.charAt(end)) < 0) end++;
        String authority = rest.substring(0, end);
        if (authority.isEmpty() || authority.indexOf('@') >= 0) return null;
        String host = authority;
        String port = "";
        if (!authority.startsWith("[")) {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                port = authority.substring(colon);
            }
            try {
                host = IDN.toASCII(host).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
            if (host.isEmpty()) return null;
        }
        String normalized = "https://" + host + port + encode(rest.substring(end));
        if (normalized.length() > MAX_URL || normalized.chars().anyMatch(c -> c <= ' ' || c >= 0x7f)) return null;
        try {
            URI uri = URI.create(normalized);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null) return null;
            return normalized;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Percent-encodes (UTF-8) what a path or query may not carry raw: non-ASCII characters and {@code \ ^ ` { | }}; existing escapes stay. */
    private static String encode(String part) {
        StringBuilder out = new StringBuilder(part.length() + 16);
        for (int index = 0; index < part.length(); ) {
            int point = part.codePointAt(index);
            index += Character.charCount(point);
            if (point < 0x7f && "\\^`{|}".indexOf(point) < 0) {
                out.append((char) point);
                continue;
            }
            for (byte octet : new String(Character.toChars(point)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                out.append('%').append(Character.toUpperCase(Character.forDigit((octet >> 4) & 0xf, 16))).append(Character.toUpperCase(Character.forDigit(octet & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /**
     * The identity of an {@linkplain #acceptable acceptable} URL for de-duplication: scheme and host in lower case, no {@code www.}, the default
     * port dropped, no trailing slash, tracking parameters ({@code utm_*}, {@code fbclid}, {@code gclid}, {@code yclid}) removed.
     */
    static String key(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        StringBuilder query = new StringBuilder();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                String lower = name.toLowerCase(Locale.ROOT);
                if (pair.isEmpty() || lower.startsWith("utm_") || lower.equals("fbclid") || lower.equals("gclid") || lower.equals("yclid")) continue;
                query.append(query.isEmpty() ? "?" : "&").append(pair);
            }
        }
        String port = uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort();
        return "https://" + host + port + path + query;
    }
}

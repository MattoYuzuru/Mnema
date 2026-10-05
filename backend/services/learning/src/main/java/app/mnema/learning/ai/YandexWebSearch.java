package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Yandex Search API v2, synchronous web search (https://aistudio.yandex.ru/docs/en/search-api/; the request and response shapes are the spike of
 * 2026-10-05 and were not run against the live service: no key exists yet). {@code POST {base}/v2/web/search} with {@code Authorization: Api-Key},
 * a JSON body (one query, {@code FORMAT_XML}, one document per domain) and a {@code {"rawData": "<base64 of XML>"}} answer. A Russian query uses the
 * Russian index in region 225, anything else the international ({@code COM}) one. The provider is reached directly from this server, never through
 * the egress proxy (it is a Russian counterparty).
 *
 * <p>The XML is untrusted: it is bounded (1 MiB decoded), parsed with a StAX reader that has DTDs, external entities and entity expansion off, and a
 * document that carries a DOCTYPE at all is rejected. Two failure layers are mapped to {@link AiFailure}: the HTTP status, and an {@code <error
 * code="N">} inside an HTTP 200 answer. The paid unit is an answered request, so {@code error code 15} (no results) is an empty success.
 */
final class YandexWebSearch implements WebSearchAdapter {
    static final String PROVIDER = "yandex";
    static final int MAX_XML_BYTES = 1_048_576;
    private static final Logger LOG = LoggerFactory.getLogger(YandexWebSearch.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_FIELD = 4_096;
    private static final DateTimeFormatter MODTIME = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ChatHttp http;
    private final URI endpoint;
    private final String key;
    private final boolean enabled;
    private final ResearchSettings settings;
    private final BigDecimal usdRubRate;
    private final Clock clock;

    YandexWebSearch(AiProperties.Provider provider, ChatHttp http, ResearchSettings settings, BigDecimal usdRubRate, Clock clock) {
        this.http = http;
        String base = provider.baseUrl().isEmpty() ? "https://searchapi.api.cloud.yandex.net" : provider.baseUrl();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v2/web/search");
        this.key = provider.apiKey();
        this.enabled = provider.enabled();
        this.settings = settings;
        this.usdRubRate = usdRubRate;
        this.clock = clock;
    }

    @Override public String provider() { return PROVIDER; }

    @Override
    public boolean configured() { return enabled && !key.isEmpty() && !settings.yandexFolderId().isEmpty() && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override public int maxQueries() { return 1; }

    @Override
    public long requestCostMicros() {
        // roubles to dollars to micro-dollars
        return settings.yandexRubPerRequest().multiply(BigDecimal.valueOf(1_000_000)).divide(usdRubRate, 0, RoundingMode.CEILING).longValue();
    }

    @Override
    public AiResult<List<WebSearch.Result>> search(WebSearch.Request request, Duration budget) {
        ObjectNode body = JSON.createObjectNode();
        boolean russian = request.lang().equals("ru");
        ObjectNode query = body.putObject("query");
        query.put("searchType", russian ? "SEARCH_TYPE_RU" : "SEARCH_TYPE_COM").put("queryText", request.queries().getFirst())
                .put("familyMode", "FAMILY_MODE_STRICT").put("page", "0").put("fixTypoMode", "FIX_TYPO_MODE_ON");
        body.putObject("sortSpec").put("sortMode", "SORT_MODE_BY_RELEVANCE").put("sortOrder", "SORT_ORDER_DESC");
        // one document per domain: the sources of a material differ from each other (page > 0 would be another paid request: never)
        body.putObject("groupSpec").put("groupMode", "GROUP_MODE_DEEP").put("groupsOnPage", Integer.toString(Math.min(request.maxResults(), 10)))
                .put("docsInGroup", "1");
        body.put("maxPassages", "3");
        if (russian) body.put("region", settings.yandexRegion());
        body.put("l10n", russian ? "LOCALIZATION_RU" : "LOCALIZATION_EN").put("folderId", settings.yandexFolderId()).put("responseFormat", "FORMAT_XML");
        HttpRequest.Builder post = HttpRequest.newBuilder(endpoint).header("Authorization", "Api-Key " + key)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        ChatHttp.Reply reply;
        try {
            reply = http.send(post, budget, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(WebSearchAdapter.transportFailure(exception));
        }
        if (reply.status() / 100 != 2) return AiResult.failed(WebSearchAdapter.statusFailure(reply.status(), reply.retryAfter(), clock));
        byte[] xml;
        try {
            String raw = JSON.readTree(reply.body()).path("rawData").stringValue(null);
            if (raw == null || raw.length() > MAX_XML_BYTES * 4L / 3 + 16) return AiResult.failed(new AiFailure.InvalidOutput("shape"));
            xml = Base64.getMimeDecoder().decode(raw);
        } catch (JacksonException | IllegalArgumentException malformed) {
            return AiResult.failed(new AiFailure.InvalidOutput("malformed"));
        }
        if (xml.length > MAX_XML_BYTES) return AiResult.failed(new AiFailure.InvalidOutput("body_too_large"));
        Parsed parsed;
        try {
            parsed = parse(xml, request.maxResults());
        } catch (XMLStreamException | RuntimeException rejected) {
            // a DOCTYPE, an entity, malformed markup: the message of the parser can echo the payload and is never kept
            LOG.warn("web_search_xml_rejected provider=yandex error_type={}", rejected.getClass().getSimpleName());
            return AiResult.failed(new AiFailure.InvalidOutput("xml"));
        }
        if (parsed.errorCode() != 0) return errorLayer(parsed.errorCode());
        List<WebSearch.Result> results = new ArrayList<>();
        for (Doc doc : parsed.docs()) {
            String url = WebSearch.acceptable(doc.url);
            if (url == null) continue;
            String title = ImageText.plain(doc.title, WebSearch.MAX_TITLE);
            results.add(new WebSearch.Result(url, title.isEmpty() ? url : title, snippet(doc), date(doc.modtime), WebSearch.Provider.YANDEX, 0,
                    results.size() + 1));
        }
        return AiResult.ok(results);
    }

    /** The meaning of {@code <error code>} in an HTTP 200 answer (the error-code table of the spike). */
    private static AiResult<List<WebSearch.Result>> errorLayer(int code) {
        return switch (code) {
            case 15 -> AiResult.ok(List.of());
            case 55 -> AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
            case 32 -> AiResult.failed(new AiFailure.RateLimited(Duration.ofMinutes(10)));
            case 31, 42, 33, 44, 48 -> AiResult.failed(new AiFailure.NotConfigured("yandex_" + code));
            case 1, 2, 18, 19, 37, 10002 -> AiResult.failed(new AiFailure.InvalidOutput("request"));
            case 100 -> AiResult.failed(new AiFailure.Transient("captcha"));
            default -> AiResult.failed(new AiFailure.Transient("yandex_error"));
        };
    }

    private static String snippet(Doc doc) {
        String joined = String.join(" … ", doc.passages);
        return ImageText.plain(joined.isBlank() ? doc.headline : joined, WebSearch.MAX_SNIPPET);
    }

    /** {@code modtime} is {@code yyyyMMdd'T'HHmmss} (no zone guaranteed): the date part, as an ISO date, or null. */
    private static String date(String modtime) {
        if (modtime == null || modtime.length() < 8) return null;
        try {
            return LocalDate.parse(modtime.substring(0, 8), MODTIME).toString();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the XML

    static final class Doc {
        String url = "";
        String title = "";
        String headline = "";
        String modtime = "";
        final List<String> passages = new ArrayList<>();
    }

    record Parsed(int errorCode, List<Doc> docs) { }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        try {
            factory.setProperty(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (IllegalArgumentException unsupported) {
            // the JDK reader may not list it as a property; DTDs and entities are off above and a DOCTYPE is refused below
        }
        return factory;
    }

    /**
     * Reads the documents of a Yandex XML answer. A DOCTYPE or an entity declaration is refused outright before the parser sees it (the parser has
     * DTDs and external entities off as well): the answer of a search engine has neither. A NUL byte is refused too, so that a UTF-16 payload cannot
     * hide a DOCTYPE from the scan.
     */
    static Parsed parse(byte[] xml, int limit) throws XMLStreamException {
        String scan = new String(xml, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (scan.indexOf('\0') >= 0 || scan.contains("<!DOCTYPE") || scan.contains("<!ENTITY")) throw new XMLStreamException("dtd");
        XMLStreamReader reader = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        try {
            int error = 0;
            boolean seenResponse = false;
            List<Doc> docs = new ArrayList<>();
            Doc doc = null;
            boolean inPassages = false;
            while (reader.hasNext() && docs.size() < limit) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw new XMLStreamException("dtd");
                if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if (name.equals("doc") && doc != null) {
                        docs.add(doc);
                        doc = null;
                    } else if (name.equals("passages")) {
                        inPassages = false;
                    }
                    continue;
                }
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                String name = reader.getLocalName();
                switch (name) {
                    case "yandexsearch" -> seenResponse = true;
                    case "error" -> {
                        String code = reader.getAttributeValue(null, "code");
                        error = code != null && code.matches("[0-9]{1,6}") ? Integer.parseInt(code) : -1;
                        text(reader);
                    }
                    case "doc" -> doc = new Doc();
                    case "passages" -> inPassages = doc != null;
                    case "url" -> {
                        String value = text(reader);
                        if (doc != null) doc.url = value.strip();
                    }
                    case "title" -> {
                        String value = text(reader);
                        if (doc != null) doc.title = value;
                    }
                    case "headline" -> {
                        String value = text(reader);
                        if (doc != null) doc.headline = value;
                    }
                    case "modtime" -> {
                        String value = text(reader);
                        if (doc != null) doc.modtime = value.strip();
                    }
                    case "passage" -> {
                        String value = text(reader);
                        if (doc != null && inPassages && doc.passages.size() < 5) doc.passages.add(value);
                    }
                    default -> { }
                }
            }
            if (!seenResponse) throw new XMLStreamException("not a yandexsearch document");
            return new Parsed(error, docs);
        } finally {
            reader.close();
        }
    }

    /** The text of the current element and everything inside it ({@code <hlword>} is mixed content), consuming its end tag; bounded. */
    private static String text(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder out = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (out.length() < MAX_FIELD) out.append(reader.getText(), 0, Math.min(reader.getText().length(), MAX_FIELD - out.length()));
                }
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> throw new XMLStreamException("dtd");
                default -> { }
            }
        }
        return out.toString();
    }
}

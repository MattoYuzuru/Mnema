package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Community/7 (#454), {@code GET /api/public/sitemap.xml}: sitemaps.org 0.9 over the listed decks, public caching, the per-instance cache and the 50 000 URL limit. */
class CatalogSitemapHttpIntegrationTest extends CatalogHttpTest {
    private static final Pattern W3C = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

    private Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private List<String> locs(Document document) {
        List<String> locs = new ArrayList<>();
        NodeList nodes = document.getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc");
        for (int index = 0; index < nodes.getLength(); index++) locs.add(nodes.item(index).getTextContent());
        return locs;
    }

    @Test
    void theSitemapListsTheCommunityPageAndEveryListedDeckWithItsSlugAndLastModification() throws Exception {
        CatalogSeed.Deck a = seed.insert(CatalogSeed.deck("Испанский язык: базовые глаголы").updatedAt(Instant.parse("2026-10-11T09:30:00.123456Z")).code("Kq7xT3mNpR"));
        CatalogSeed.Deck b = seed.insert(CatalogSeed.deck("Tom & Jerry <script> \"quotes\" 'x'").code("Zp4WcA8vQe").listedAt(Instant.parse("2026-09-02T00:00:00Z")));
        seed.insert(CatalogSeed.deck("Ниже порога").items(2));
        seed.insert(CatalogSeed.deck("Без описания").description(""));
        service.invalidate();
        HttpResponse<String> response = get("/public/sitemap.xml");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("public, max-age=3600");
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/xml").containsIgnoringCase("utf-8"));
        Document document = parse(response.body());
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("urlset");
        assertThat(document.getDocumentElement().getNamespaceURI()).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        assertThat(locs(document)).containsExactly("https://mnema.app/community", "https://mnema.app/d/Zp4WcA8vQe/tom-jerry-script-quotes-x", "https://mnema.app/d/Kq7xT3mNpR/ispanskiy-yazyk-bazovye-glagoly");
        NodeList lastmods = document.getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "lastmod");
        assertThat(lastmods.getLength()).isEqualTo(2);
        for (int index = 0; index < lastmods.getLength(); index++) assertThat(W3C.matcher(lastmods.item(index).getTextContent()).matches()).isTrue();
        assertThat(lastmods.item(1).getTextContent()).isEqualTo("2026-10-11T09:30:00Z");
        assertThat(response.body()).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        assertThat(a.listed() && b.listed()).isTrue();
        // an answer for everybody: a bearer changes nothing, a stale one is still refused by the chain
        assertThat(get("/public/sitemap.xml", java.util.UUID.randomUUID()).body()).isEqualTo(response.body());
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/sitemap.xml", "not-a-token", null, Map.of()).statusCode()).isEqualTo(401);
        assertThat(HttpIdentityFixture.send(port, "POST", "/public/sitemap.xml", null, "{}", Map.of()).statusCode()).isIn(401, 403, 405);
    }

    @Test
    void anEmptyCatalogueStillHasTheCommunityPageAndTheDocumentIsCachedPerInstance() throws Exception {
        assertThat(locs(parse(get("/public/sitemap.xml").body()))).containsExactly("https://mnema.app/community");
        seed.insert(CatalogSeed.deck("Новая"));
        assertThat(locs(parse(get("/public/sitemap.xml").body()))).as("cached for an hour").hasSize(1);
        service.invalidate();
        assertThat(locs(parse(get("/public/sitemap.xml").body()))).hasSize(2);
    }

    @Test
    void headIsAnsweredWithTheSameHeadersAndNoBody() throws Exception {
        seed.insert(CatalogSeed.deck("Одна"));
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/public/sitemap.xml")).method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> head = java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("cache-control")).contains("public, max-age=3600");
    }

    @Test
    void aCatalogueBeyondFiftyThousandUrlsIsTruncatedWithAWarning() throws Exception {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("""
                    INSERT INTO app_learning.public_deck(deck_id, public_code, owner_id, published_revision_id, title, description, item_count, exercise_count, first_listed_at, content_updated_at, title_norm, projected_at)
                    SELECT gen_random_uuid(), translate(substr(md5(g::text), 1, 10), '0', '1'), gen_random_uuid(), gen_random_uuid(), 'Колода ' || g, 'Описание', 10, 1,
                           now() - g * interval '1 second', now(), 'колода ' || g, now()
                      FROM generate_series(1, 50005) g
                    """).update();
        });
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        HttpResponse<String> response;
        try {
            service.invalidate();
            response = get("/public/sitemap.xml");
        } finally {
            root.detachAppender(appender);
        }
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(locs(parse(response.body()))).hasSize(50_000);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getFormattedMessage()).contains("catalog_sitemap_truncated");
        });
    }
}

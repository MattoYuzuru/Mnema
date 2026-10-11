package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.MediaFixtures.Variants;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/9 (#431) on the real servlet stack: the media of someone else's deck, for guests and accounts, through {@code GET /api/public/decks/{code}/media/{assetId}},
 * and the copy owner's own deck on the private routes. The matrix {owner, grantee, link viewer, guest, copy owner} x {PRIVATE, LINK, INVITE, PUBLIC} gives a URL of a
 * derived variant or the one 404; a change that is not published, a removed asset and an asset of another deck are 404 for everybody; the original never leaves the
 * owner's own route. The object store is never reached: links are signed offline for an unreachable loopback endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicMediaHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private PublicationService publication;
    @Autowired private DeckPublications deckPublications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MeterRegistry meters;
    private SharedMediaWorlds worlds;
    private SharedScopeFixture shared;
    private StudyFixtures study;

    private final UUID author = UUID.randomUUID();
    private final UUID grantee = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();
    private final UUID copyOwner = UUID.randomUUID();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.coarse-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
        registry.add("learning.community.public-media.enabled", () -> true);
        registry.add("learning.community.public-media.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-media.deck-per-minute", () -> 1_000_000);
        // an unreachable loopback endpoint: URLs are signed offline, nothing is ever fetched
        registry.add("learning.media.upload.endpoint", () -> "http://127.0.0.1:9");
        registry.add("learning.media.upload.allow-loopback-http", () -> true);
        registry.add("learning.media.upload.bucket", () -> "shared-media-test");
        registry.add("learning.media.upload.access-key", () -> "AKIATESTONLY");
        registry.add("learning.media.upload.secret-key", () -> "test-only-signing-material");
    }

    @BeforeEach
    void fixtures() {
        worlds = new SharedMediaWorlds(author, publication, deckPublications, decks, items, exercises, sessions, media, storage, jdbc, transactions);
        shared = worlds.shared();
        study = worlds.study();
    }

    private SharedMediaWorlds.World world(String level) { return worlds.world(level, grantee); }

    private void save(UUID deck, String level, boolean publishHead) { worlds.save(deck, level, publishHead); }

    private HttpResponse<String> get(String path, UUID account) {
        return HttpIdentityFixture.send(port, "GET", path, account == null ? null : HttpIdentityFixture.reader(account), null, Map.of());
    }

    private HttpResponse<String> media(String code, UUID asset, UUID account) { return get("/public/decks/" + code + "/media/" + asset, account); }

    private static JsonNode json(HttpResponse<String> response) {
        try { return JSON.readTree(response.body()); } catch (RuntimeException failure) { throw new AssertionError(response.body(), failure); }
    }

    /** A 404 with the request path removed: every refusal must be the same bytes whatever the reason. */
    private static String notFound(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(response.body()).doesNotContain("http://").doesNotContain("X-Amz");
        var body = (tools.jackson.databind.node.ObjectNode) json(response);
        assertThat(body.path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
        body.remove("instance");
        return body.toString();
    }

    private static void granted(HttpResponse<String> response, UUID asset, Variants variants, boolean poster) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).hasValue("private, no-store");
        assertThat(response.headers().firstValue("vary")).hasValueSatisfying(value -> assertThat(value).containsIgnoringCase("Authorization"));
        JsonNode body = json(response);
        // exactly the contract shape: no download, no original, no object key
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("assetId", "state", "playback", "poster");
        assertThat(body.path("assetId").stringValue(null)).isEqualTo(asset.toString());
        assertThat(body.path("state").stringValue(null)).isEqualTo("READY");
        assertThat(body.path("playback").propertyNames()).containsExactlyInAnyOrder("url", "expiresAt", "mimeType");
        assertThat(body.path("playback").path("url").stringValue(null)).contains("/" + variants.playbackKey()).contains("X-Amz-Signature").doesNotContain(variants.originalKey());
        assertThat(body.path("playback").path("mimeType").stringValue(null)).isEqualTo(variants.playbackMime());
        if (poster) {
            assertThat(body.path("poster").path("url").stringValue(null)).contains("/" + variants.posterKey()).doesNotContain(variants.originalKey());
            assertThat(body.path("poster").path("mimeType").stringValue(null)).isEqualTo("image/webp");
        } else {
            assertThat(body.path("poster").isNull()).isTrue();
        }
        assertThat(response.body()).doesNotContain(variants.originalKey()).doesNotContain("attachment");
    }

    private enum Who { OWNER, GRANTEE, LINK_VIEWER, GUEST, COPY_OWNER }

    private UUID account(Who who) {
        return switch (who) {
            case OWNER -> author;
            case GRANTEE -> grantee;
            case LINK_VIEWER -> viewer;
            case GUEST -> null;
            case COPY_OWNER -> copyOwner;
        };
    }

    private static boolean readable(String level, Who who) {
        return switch (level) {
            case "PRIVATE" -> who == Who.OWNER;
            case "INVITE" -> who == Who.OWNER || who == Who.GRANTEE;
            default -> true;
        };
    }

    @Test
    void everyViewerGetsADerivedVariantExactlyWhenHeCanReadTheDeckAndTheSame404Otherwise() {
        String unknown = notFound(media(PublicCodes.next(new java.security.SecureRandom()), UUID.randomUUID(), null));
        for (String level : List.of("PRIVATE", "LINK", "INVITE", "PUBLIC")) {
            SharedMediaWorlds.World world = world(level);
            Copy copy = shared.copy(world.deck(), copyOwner);
            assertThat(copy.owner()).isEqualTo(copyOwner);
            for (Who who : Who.values()) {
                String label = level + "/" + who;
                HttpResponse<String> response = media(world.code(), world.image(), account(who));
                if (readable(level, who)) granted(response, world.image(), world.imageVariants(), true);
                else assertThat(notFound(response)).as(label).isEqualTo(unknown);
                // the sound belongs to an exercise: the public view shows exercises as text, so no guest, grantee or owner gets it here
                assertThat(notFound(media(world.code(), world.sound(), account(who)))).as(label + " exercise media").isEqualTo(unknown);
            }
        }
    }

    @Test
    void anUnpublishedChangeAndARemovedAssetAreNotServedAndAnAssetOfAnotherDeckNever() {
        SharedMediaWorlds.World world = world("LINK");
        UUID fresh = study.readyAsset(author, "image/png");
        Variants freshVariants = MediaFixtures.variants(jdbc, fresh, "image/webp");
        SharedMediaWorlds.World other = world("LINK");
        String refusal = notFound(media(world.code(), UUID.randomUUID(), null));

        // a new image in the head: the owner's draft of the next publication, invisible to readers (and to the owner on the public route)
        UUID second = MediaFixtures.saveImages(decks, items, author, world.deck(), world.material().member(), world.material().revision(), world.image(), fresh);
        for (UUID account : new UUID[] {null, viewer, author, grantee}) {
            assertThat(notFound(media(world.code(), fresh, account))).isEqualTo(refusal);
            granted(media(world.code(), world.image(), account), world.image(), world.imageVariants(), true);
        }
        // the other deck's published asset is not in this deck's set
        for (UUID account : new UUID[] {null, viewer, author}) {
            assertThat(notFound(media(world.code(), other.image(), account))).isEqualTo(refusal);
            assertThat(notFound(media(other.code(), world.image(), account))).isEqualTo(refusal);
        }

        // published: the new image is served
        save(world.deck(), "LINK", true);
        granted(media(world.code(), fresh, null), fresh, freshVariants, true);

        // a copy made now keeps both; the author then removes the first image and republishes
        Copy copy = shared.copy(world.deck(), copyOwner);
        UUID third = MediaFixtures.saveImages(decks, items, author, world.deck(), world.material().member(), second, fresh);
        assertThat(third).isNotEqualTo(second);
        granted(media(world.code(), world.image(), null), world.image(), world.imageVariants(), true);
        save(world.deck(), "LINK", true);
        for (UUID account : new UUID[] {null, viewer, grantee, author}) {
            assertThat(notFound(media(world.code(), world.image(), account))).isEqualTo(refusal);
            granted(media(world.code(), fresh, account), fresh, freshVariants, true);
            assertThat(notFound(media(world.code(), world.sound(), account))).isEqualTo(refusal);
        }
        // the copy's owner still holds the removed image in HIS heads: his own routes keep serving it (variants only)
        HttpResponse<String> inherited = get("/media-assets/" + world.image() + "/playback", copyOwner);
        assertThat(inherited.statusCode()).as(inherited.body()).isEqualTo(200);
        assertThat(json(inherited).path("download").isNull()).isTrue();
        assertThat(copy.owner()).isEqualTo(copyOwner);
    }

    @Test
    void aPendingAssetIsReportedWithoutLinksAndMalformedIdentifiersAre400() {
        SharedMediaWorlds.World world = world("LINK");
        UUID pending = study.pendingAsset(author);
        MediaFixtures.saveImages(decks, items, author, world.deck(), world.material().member(), world.material().revision(), world.image(), pending);
        save(world.deck(), "LINK", true);
        HttpResponse<String> response = media(world.code(), pending, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("state").stringValue(null)).isIn("PENDING_UPLOAD", "PROCESSING");
        assertThat(body.path("playback").isNull()).isTrue();
        assertThat(body.path("poster").isNull()).isTrue();
        assertThat(get("/public/decks/" + world.code() + "/media/not-a-uuid", null).statusCode()).isEqualTo(400);
        assertThat(get("/public/decks/short/media/" + world.image(), null).statusCode()).isEqualTo(404);
        // an invalid bearer is never a guest
        assertThat(HttpIdentityFixture.send(port, "GET", "/public/decks/" + world.code() + "/media/" + world.image(), "garbage", null, Map.of()).statusCode()).isEqualTo(401);
        // only reads exist
        assertThat(HttpIdentityFixture.send(port, "POST", "/public/decks/" + world.code() + "/media/" + world.image(), null, "{}", Map.of()).statusCode()).isIn(401, 403, 405);
    }

    @Test
    void theCopyOwnerPlaysInheritedMediaOnHisOwnRoutesAsVariantsOnlyAndAStrangerNeverDoes() {
        SharedMediaWorlds.World world = world("PRIVATE");
        Copy copy = shared.copy(world.deck(), copyOwner);
        String refusal = notFound(media(world.code(), UUID.randomUUID(), null));

        // the author reads both derived variants and the original
        HttpResponse<String> own = get("/media-assets/" + world.image() + "/playback", author);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(json(own).path("download").path("url").stringValue(null)).contains(world.imageVariants().originalKey());

        // the copy owner: variants, no download, for the image of a material and the sound of an exercise
        for (UUID asset : List.of(world.image(), world.sound())) {
            HttpResponse<String> response = get("/media-assets/" + asset + "/playback", copyOwner);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode body = json(response);
            assertThat(body.path("state").stringValue(null)).isEqualTo("READY");
            assertThat(body.path("playback").path("url").stringValue(null)).contains("variant/").doesNotContain("/k/");
            assertThat(body.path("download").isNull()).isTrue();
            String original = asset.equals(world.image()) ? world.imageVariants().originalKey() : world.soundVariants().originalKey();
            assertThat(response.body()).doesNotContain(original);
        }
        // the offline route serves a derived variant of an inherited asset, and cannot be asked for the original
        HttpResponse<String> variant = get("/media-assets/" + world.image() + "/variants/" + world.imageVariants().playback() + "/download", copyOwner);
        assertThat(variant.statusCode()).as(variant.body()).isEqualTo(200);
        assertThat(json(variant).path("url").stringValue(null)).contains(world.imageVariants().playbackKey());

        // a stranger (an account that owns neither the asset nor a deck whose heads hold it) gets nothing, on every route
        for (UUID stranger : List.of(viewer, grantee)) {
            assertThat(get("/media-assets/" + world.image() + "/playback", stranger).statusCode()).isEqualTo(404);
            assertThat(get("/media-assets/" + world.image() + "/variants/" + world.imageVariants().playback() + "/download", stranger).statusCode()).isEqualTo(404);
            assertThat(get("/decks/" + copy.deckId() + "/media-manifests/current", stranger).statusCode()).isEqualTo(404);
        }
        // the heads decide: a deck deleted by its owner no longer opens the media of its lineage
        jdbc.sql("UPDATE app_learning.deck SET deleted_at = now() WHERE deck_id = :deck").param("deck", copy.deckId()).update();
        assertThat(get("/media-assets/" + world.image() + "/playback", copyOwner).statusCode()).isEqualTo(404);
        assertThat(refusal).isNotBlank();
    }

    @Test
    void theOutcomesAreCountedWithoutTheCodeOrTheAssetInALabel() {
        SharedMediaWorlds.World world = world("LINK");
        double ok = count("ok"), missing = count("not_found");
        assertThat(media(world.code(), world.image(), null).statusCode()).isEqualTo(200);
        assertThat(media(world.code(), UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        assertThat(count("ok")).isEqualTo(ok + 1);
        assertThat(count("not_found")).isEqualTo(missing + 1);
        List<String> labels = new ArrayList<>();
        meters.find("mnema_public_media_requests_total").counters().forEach(counter -> counter.getId().getTags().forEach(tag -> labels.add(tag.getKey())));
        // besides the registry's common tags, the one label is the outcome
        assertThat(labels).allMatch(name -> List.of("outcome", "application", "environment").contains(name));
    }

    private static JsonNode contract() {
        try {
            java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
            while (!java.nio.file.Files.exists(root.resolve("contracts/decks/public-read.json"))) root = root.getParent();
            return JSON.readTree(java.nio.file.Files.readString(root.resolve("contracts/decks/public-read.json")));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static java.util.Set<String> names(JsonNode node) {
        java.util.Set<String> result = new java.util.TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    @Test
    void theContractFixtureHasExactlyTheShapeOfTheRealMediaAnswers() throws Exception {
        JsonNode contract = contract();
        JsonNode examples = contract.path("media");
        SharedMediaWorlds.World world = world("LINK");
        UUID pending = study.pendingAsset(author);
        UUID quiet = study.readyAsset(author, "image/png");
        MediaFixtures.variants(jdbc, quiet, "image/webp", false);
        MediaFixtures.saveImages(decks, items, author, world.deck(), world.material().member(), world.material().revision(), world.image(), pending, quiet);
        save(world.deck(), "LINK", true);
        JsonNode withPoster = json(media(world.code(), world.image(), null));
        JsonNode withoutPoster = json(media(world.code(), quiet, null));
        JsonNode notReady = json(media(world.code(), pending, null));
        assertThat(names(withPoster)).isEqualTo(names(examples.path("ready")));
        assertThat(names(withPoster.path("playback"))).isEqualTo(names(examples.path("ready").path("playback")));
        assertThat(names(withPoster.path("poster"))).isEqualTo(names(examples.path("ready").path("poster")));
        assertThat(names(withoutPoster)).isEqualTo(names(examples.path("readyWithoutPoster")));
        assertThat(withoutPoster.path("poster").isNull()).isEqualTo(examples.path("readyWithoutPoster").path("poster").isNull());
        assertThat(names(notReady)).isEqualTo(names(examples.path("notReady")));
        assertThat(notReady.path("playback").isNull()).isEqualTo(examples.path("notReady").path("playback").isNull());
        assertThat(names(examples.path("unavailable"))).isEqualTo(names(examples.path("notReady")));
        assertThat(contract.path("constants").path("mediaStates").valueStream().map(JsonNode::stringValue)).contains(notReady.path("state").stringValue(null));
        assertThat(contract.path("routes").valueStream().map(route -> route.path("path").stringValue(null))).contains("/api/public/decks/{code}/media/{assetId}");
        // the documented defaults are the real ones
        var parameters = PublicMediaSettings.class.getDeclaredConstructors()[0].getParameters();
        java.util.List<String> declared = new ArrayList<>();
        for (var parameter : parameters) declared.add(parameter.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value());
        JsonNode limits = contract.path("constants").path("mediaRateLimit");
        assertThat(declared).anyMatch(value -> value.endsWith("public-media.enabled:false}"))
                .anyMatch(value -> value.endsWith("guest-per-minute:" + limits.path("guestPerMinute").intValue() + "}"))
                .anyMatch(value -> value.endsWith("coarse-per-minute:" + limits.path("coarsePerMinute").intValue() + "}"))
                .anyMatch(value -> value.endsWith("deck-per-minute:" + limits.path("deckPerMinute").intValue() + "}"))
                .anyMatch(value -> value.endsWith("overflow-per-minute:" + limits.path("overflowPerMinute").intValue() + "}"));
        var ttl = app.mnema.learning.media.SharedMediaPlayback.class.getDeclaredConstructors()[0].getParameters()[2].getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        assertThat(ttl).endsWith("public-media.url-ttl:" + contract.path("constants").path("mediaUrlTtl").stringValue(null).split(" ")[0] + "}");
        for (String example : List.of("ready", "readyWithoutPoster", "notReady", "unavailable")) {
            assertThat(examples.path(example).toString()).doesNotContain("download", "original", "objectKey");
        }
    }

    @Test
    void linksAreShortLivedAndUnavailableStatesAreCollapsedForNonOwners() {
        SharedMediaWorlds.World world = world("LINK");
        UUID processing = study.pendingAsset(author);
        UUID deleted = study.readyAsset(author, "image/png");
        UUID rejected = study.pendingAsset(author);
        UUID failed = study.pendingAsset(author);
        MediaFixtures.saveImages(decks, items, author, world.deck(), world.material().member(), world.material().revision(), world.image(), processing, deleted, rejected, failed);
        save(world.deck(), "LINK", true);
        jdbc.sql("UPDATE app_learning.media_asset SET state = 'PROCESSING' WHERE asset_id = :asset").param("asset", processing).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state = 'DELETED' WHERE asset_id = :asset").param("asset", deleted).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state = 'REJECTED' WHERE asset_id = :asset").param("asset", rejected).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state = 'FAILED_RETRYABLE' WHERE asset_id = :asset").param("asset", failed).update();
        for (UUID account : new UUID[] {null, viewer}) {
            assertThat(json(media(world.code(), processing, account)).path("state").stringValue(null)).isEqualTo("PROCESSING");
            for (UUID gone : List.of(deleted, rejected, failed)) {
                JsonNode body = json(media(world.code(), gone, account));
                assertThat(body.path("state").stringValue(null)).isEqualTo("UNAVAILABLE");
                assertThat(body.path("playback").isNull()).isTrue();
                assertThat(body.path("poster").isNull()).isTrue();
            }
        }
        // the signed links of a guest live ten minutes (default), not the hour of the owner's playback
        JsonNode ready = json(media(world.code(), world.image(), null));
        java.time.Instant expires = java.time.Instant.parse(ready.path("playback").path("expiresAt").stringValue(null));
        assertThat(expires).isBetween(java.time.Instant.now().plusSeconds(8 * 60), java.time.Instant.now().plusSeconds(11 * 60));
        JsonNode own = json(get("/media-assets/" + world.image() + "/playback", author));
        assertThat(java.time.Instant.parse(own.path("playback").path("expiresAt").stringValue(null))).isAfter(java.time.Instant.now().plusSeconds(50 * 60));
    }

    private double count(String outcome) {
        var counter = meters.find("mnema_public_media_requests_total").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}

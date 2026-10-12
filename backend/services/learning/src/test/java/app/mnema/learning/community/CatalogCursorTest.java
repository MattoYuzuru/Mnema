package app.mnema.learning.community;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The opaque cursor of the catalogue lists: what it holds, what it refuses, and that it is bound to a list and a query. */
class CatalogCursorTest {
    private static final String HASH = "M9bQwCWdh4qR";

    private static String raw(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyKindRoundTripsAndNothingElseIsInIt() {
        CatalogCursor rank = new CatalogCursor("popular", HASH, 7L, 12L, null, null, null);
        assertThat(CatalogCursor.decode(rank.encode(), "popular", HASH)).isEqualTo(rank);
        assertThat(new String(Base64.getUrlDecoder().decode(rank.encode()), StandardCharsets.UTF_8)).isEqualTo("{\"v\":1,\"k\":\"popular\",\"h\":\"M9bQwCWdh4qR\",\"g\":7,\"p\":12}");
        Instant at = Instant.parse("2026-10-11T09:30:00.123456Z");
        CatalogCursor time = new CatalogCursor("new", HASH, null, null, at, "Kq7xT3mNpR", null);
        assertThat(CatalogCursor.decode(time.encode(), "new", HASH)).isEqualTo(time);
        assertThat(CatalogCursor.decode(time.encode(), "new", HASH).at()).as("microseconds survive").isEqualTo(at);
        CatalogCursor search = new CatalogCursor("search", HASH, null, 418_113L, null, "Kq7xT3mNpR", null);
        assertThat(CatalogCursor.decode(search.encode(), "search", HASH)).isEqualTo(search);
        CatalogCursor author = new CatalogCursor("author", HASH, null, null, at, "Kq7xT3mNpR", "grants");
        assertThat(CatalogCursor.decode(author.encode(), "author", HASH)).isEqualTo(author);
        assertThat(CatalogCursor.decode(null, "new", HASH)).isNull();
    }

    @Test
    void aCursorOfAnotherListOrQueryIsRefused() {
        String cursor = new CatalogCursor("popular", HASH, 7L, 12L, null, null, null).encode();
        assertThatThrownBy(() -> CatalogCursor.decode(cursor, "learning", HASH)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> CatalogCursor.decode(cursor, "popular", "another-hash")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void malformedAndForgedCursorsAreRefused() {
        for (String bad : new String[] {"", "!", "====", "%%", "a".repeat(301), raw("[]"), raw("null"), raw("{}"), raw("{\"v\":2,\"k\":\"popular\",\"h\":\"" + HASH + "\"}"),
                raw("{\"v\":1,\"k\":\"popular\",\"h\":\"" + HASH + "\",\"extra\":1}"), raw("{\"v\":1,\"k\":\"popular\",\"h\":\"" + HASH + "\",\"p\":-1}"),
                raw("{\"v\":1,\"k\":\"popular\",\"h\":\"" + HASH + "\",\"p\":1.5}"), raw("{\"v\":1,\"k\":\"popular\",\"h\":\"" + HASH + "\",\"p\":\"1\"}"),
                raw("{\"v\":1,\"k\":\"popular\",\"h\":\"" + HASH + "\",\"g\":99999999999999999999}"), raw("{\"v\":1,\"k\":\"popular\",\"k\":\"popular\",\"h\":\"" + HASH + "\"}"),
                // keys in another order, spaces: not exactly what encode writes
                raw("{\"k\":\"popular\",\"v\":1,\"h\":\"" + HASH + "\",\"g\":7,\"p\":12}"), raw("{\"v\":1, \"k\":\"popular\",\"h\":\"" + HASH + "\",\"g\":7,\"p\":12}"),
                raw("{\"v\":1,\"k\":\"popular\",\"h\":\"" + HASH + "\",\"c\":\"" + "x".repeat(40) + "\"}")}) {
            assertThatThrownBy(() -> CatalogCursor.decode(bad, "popular", HASH)).as(bad).isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void thePositionsAChosenListNeedsAreChecked() {
        assertThatThrownBy(() -> new CatalogCursor("popular", HASH, null, 3L, null, null, null).requireRank()).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new CatalogCursor("popular", HASH, 1L, 0L, null, null, null).requireRank()).isInstanceOf(InvalidRequestException.class);
        assertThat(new CatalogCursor("popular", HASH, 1L, 1L, null, null, null).requireRank()).isNotNull();
        assertThatThrownBy(() -> new CatalogCursor("new", HASH, null, null, Instant.EPOCH, "bad", null).requireTime()).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new CatalogCursor("new", HASH, null, null, null, "Kq7xT3mNpR", null).requireTime()).isInstanceOf(InvalidRequestException.class);
        assertThat(new CatalogCursor("new", HASH, null, null, Instant.EPOCH, "Kq7xT3mNpR", null).requireTime()).isNotNull();
    }
}

package app.mnema.learning.ai;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The 24-hour answer cache of image search on PostgreSQL: a hit inside the TTL, a miss after it, the size bound, the sweep. */
@SpringBootTest
class ImageSearchCacheIntegrationTest extends PostgresIntegrationTest {
    @Autowired private JdbcImageSearchCache cache;
    @Autowired private JdbcClient jdbc;

    private static ImageSearch.Candidate candidate(String id) {
        return new ImageSearch.Candidate(ImageSearch.Source.PIXABAY, id, "Лиса", "Ann", "Pixabay Content License", "https://pixabay.com/service/license-summary/",
                "https://pixabay.com/photos/fox-" + id + "/", false, 640, 427, "https://pixabay.com/get/g" + id + ".jpg");
    }

    @Test
    void anAnswerIsServedFromTheCacheForTwentyFourHoursAndNotAfter() {
        String query = "лиса " + UUID.randomUUID();
        assertThat(cache.get("PIXABAY", query, "ru", 1)).isEmpty();

        cache.put("PIXABAY", query, "ru", 1, List.of(candidate("1"), candidate("2")));

        assertThat(cache.get("PIXABAY", "  " + query.toUpperCase() + " ", "ru", 1)).hasValueSatisfying(list -> assertThat(list).containsExactly(candidate("1"), candidate("2")));
        assertThat(cache.get("PIXABAY", query, "en", 1)).as("another language").isEmpty();
        assertThat(cache.get("WIKIMEDIA", query, "ru", 1)).as("another source").isEmpty();
        assertThat(cache.get("PIXABAY", query, "ru", 2)).as("another page").isEmpty();
        // 23 hours old is a hit, 25 hours old is a miss
        jdbc.sql("UPDATE app_learning.image_search_cache SET fetched_at=CURRENT_TIMESTAMP - interval '23 hours' WHERE cache_key=:key")
                .param("key", JdbcImageSearchCache.key("PIXABAY", query, "ru", 1)).update();
        assertThat(cache.get("PIXABAY", query, "ru", 1)).isPresent();
        jdbc.sql("UPDATE app_learning.image_search_cache SET fetched_at=CURRENT_TIMESTAMP - interval '25 hours' WHERE cache_key=:key")
                .param("key", JdbcImageSearchCache.key("PIXABAY", query, "ru", 1)).update();
        assertThat(cache.get("PIXABAY", query, "ru", 1)).isEmpty();
        // a new answer replaces the stale row
        cache.put("PIXABAY", query, "ru", 1, List.of(candidate("3")));
        assertThat(cache.get("PIXABAY", query, "ru", 1)).hasValueSatisfying(list -> assertThat(list).containsExactly(candidate("3")));
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.image_search_cache WHERE cache_key=:key").param("key", JdbcImageSearchCache.key("PIXABAY", query, "ru", 1))
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theSweepDeletesOnlyExpiredRowsAndAnOversizedAnswerIsNotStored() {
        String old = "старый " + UUID.randomUUID();
        String fresh = "свежий " + UUID.randomUUID();
        cache.put("PIXABAY", old, "ru", 1, List.of(candidate("1")));
        cache.put("PIXABAY", fresh, "ru", 1, List.of(candidate("2")));
        jdbc.sql("UPDATE app_learning.image_search_cache SET fetched_at=CURRENT_TIMESTAMP - interval '30 hours' WHERE cache_key=:key")
                .param("key", JdbcImageSearchCache.key("PIXABAY", old, "ru", 1)).update();

        cache.sweep();

        assertThat(rows(old)).isZero();
        assertThat(rows(fresh)).isEqualTo(1);
        List<ImageSearch.Candidate> huge = new java.util.ArrayList<>();
        for (int index = 0; index < 400; index++) huge.add(candidate("9" + index + "x".repeat(500)));
        String big = "огромный " + UUID.randomUUID();
        cache.put("PIXABAY", big, "ru", 1, huge);
        assertThat(rows(big)).isZero();
    }

    @Test
    void aCorruptRowIsAnEmptyAnswerNotAnError() {
        assertThat(JdbcImageSearchCache.read("[{\"source\":\"NOPE\"}]")).isEmpty();
        assertThat(JdbcImageSearchCache.read("not json")).isEmpty();
        assertThat(JdbcImageSearchCache.read(JdbcImageSearchCache.write(List.of(candidate("5"))))).containsExactly(candidate("5"));
    }

    private int rows(String query) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.image_search_cache WHERE cache_key=:key").param("key", JdbcImageSearchCache.key("PIXABAY", query, "ru", 1))
                .query(Integer.class).single();
    }
}

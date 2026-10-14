package app.mnema.learning.community;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V66 (Community/0, #447) on a real PostgreSQL: both extensions live in app_learning and the simple_unaccent configuration folds marks and «ё». */
@SpringBootTest
class CatalogSearchDictionariesIntegrationTest extends PostgresIntegrationTest {
    @Autowired private JdbcClient jdbc;

    @Test
    void bothExtensionsAreInstalledInTheLearningSchema() {
        List<String> installed = jdbc.sql("""
                SELECT e.extname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                WHERE n.nspname = 'app_learning' AND e.extname IN ('pg_trgm', 'unaccent') ORDER BY 1""").query(String.class).list();
        assertThat(installed).containsExactly("pg_trgm", "unaccent");
    }

    @Test
    void trigramsCoverCyrillicAndToleratePartialWords() {
        Integer trigrams = jdbc.sql("SELECT cardinality(app_learning.show_trgm('Привет'))").query(Integer.class).single();
        assertThat(trigrams).isEqualTo(7);
        Double similarity = jdbc.sql("SELECT app_learning.word_similarity('испанск', 'испанские глаголы')").query(Double.class).single();
        assertThat(similarity).isGreaterThan(0.8);
    }

    @Test
    void simpleUnaccentFoldsMarksAndYoWithoutStemming() {
        String vector = jdbc.sql("SELECT to_tsvector('app_learning.simple_unaccent', 'Español Crème Ёлка Глаголы')::text").query(String.class).single();
        assertThat(vector).isEqualTo("'creme':2 'espanol':1 'глаголы':4 'елка':3");
        Boolean matches = jdbc.sql("""
                SELECT to_tsvector('app_learning.simple_unaccent', 'Español para principiantes')
                    @@ websearch_to_tsquery('app_learning.simple_unaccent', 'espanol')""").query(Boolean.class).single();
        assertThat(matches).isTrue();
    }

    @Test
    void theConfigurationIsUsableWithoutTheSearchPath() {
        // A generated column or a query from another schema must not depend on the session's search_path.
        String vector = jdbc.sql("SELECT set_config('search_path', 'pg_catalog', true), to_tsvector('app_learning.simple_unaccent', 'Résumé')::text")
                .query((rs, row) -> rs.getString(2)).single();
        assertThat(vector).isEqualTo("'resume':1");
    }
}

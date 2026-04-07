package app.mnema.core.deck.domain.type;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSourceTest {

    @Test
    void enumContainsExpectedValues() {
        assertThat(ReviewSource.values()).containsExactly(
                ReviewSource.web,
                ReviewSource.mobile,
                ReviewSource.api,
                ReviewSource.other
        );
    }
}

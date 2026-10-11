package app.mnema.learning.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicReadBulkheadTest {
    private static PublicReadBulkhead bulkhead(int places) {
        return new PublicReadBulkhead(new PublicRouteSettings(true, 10, 10, 10, 10, 100, places));
    }

    @Test
    void guestsNeverTakeTheLastPlaceAndTheExcessIsRefusedAtOnce() {
        PublicReadBulkhead bulkhead = bulkhead(3);
        assertThat(bulkhead.tryAcquire(true)).isTrue();
        assertThat(bulkhead.tryAcquire(true)).isTrue();
        assertThat(bulkhead.tryAcquire(true)).as("a place stays free for a signed-in viewer").isFalse();
        assertThat(bulkhead.tryAcquire(false)).isTrue();
        assertThat(bulkhead.tryAcquire(false)).isFalse();
        assertThat(bulkhead.tryAcquire(true)).isFalse();
        bulkhead.release(true);
        assertThat(bulkhead.tryAcquire(true)).isTrue();
    }

    @Test
    void aRefusedGuestHoldsNoGuestPlaceBack() {
        PublicReadBulkhead bulkhead = bulkhead(2);
        assertThat(bulkhead.tryAcquire(false)).isTrue();
        assertThat(bulkhead.tryAcquire(false)).isTrue();
        // all places are taken by accounts: the guest is refused and the guest share is not leaked
        assertThat(bulkhead.tryAcquire(true)).isFalse();
        bulkhead.release(false);
        bulkhead.release(false);
        assertThat(bulkhead.tryAcquire(true)).isTrue();
        assertThat(bulkhead.tryAcquire(true)).isFalse();
    }
}

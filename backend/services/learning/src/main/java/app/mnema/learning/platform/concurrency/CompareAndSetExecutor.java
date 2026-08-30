package app.mnema.learning.platform.concurrency;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Transactional guard for repository updates of the form
 * {@code ... SET row_version = row_version + 1 WHERE ... AND row_version = :expectedVersion}.
 */
@Component
public class CompareAndSetExecutor {

    @Transactional
    public long updateOne(long expectedVersion, IntSupplier update) {
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException("expectedVersion must allow one non-negative increment");
        }
        int updatedRows = Objects.requireNonNull(update, "update").getAsInt();
        if (updatedRows == 0) {
            throw new VersionConflictException();
        }
        if (updatedRows != 1) {
            throw new IllegalStateException("A compare-and-set update must affect exactly one row");
        }
        return expectedVersion + 1;
    }
}

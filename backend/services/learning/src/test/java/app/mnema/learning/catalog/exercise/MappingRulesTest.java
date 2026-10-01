package app.mnema.learning.catalog.exercise;

import app.mnema.learning.catalog.exercise.MappingRules.Link;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** One totality core, two deliberately different cardinality rules. */
class MappingRulesTest {
    private static final UUID A = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("0a000000-0000-4000-8000-000000000002");
    private static final UUID C = UUID.fromString("0a000000-0000-4000-8000-000000000003");
    private static final UUID X = UUID.fromString("0b000000-0000-4000-8000-000000000001");
    private static final UUID Y = UUID.fromString("0b000000-0000-4000-8000-000000000002");
    private static final UUID Z = UUID.fromString("0b000000-0000-4000-8000-000000000003");

    @Test
    void aBijectionUsesEverySourceAndEveryTargetExactlyOnce() {
        Set<UUID> sources = Set.of(A, B);
        Set<UUID> targets = Set.of(X, Y);
        assertThat(MappingRules.bijection(List.of(new Link(A, X), new Link(B, Y)), sources, targets)).isTrue();
        assertThat(MappingRules.bijection(List.of(new Link(B, X), new Link(A, Y)), sources, targets)).isTrue();
        // two sources on one target: not a bijection even though every source is mapped
        assertThat(MappingRules.bijection(List.of(new Link(A, X), new Link(B, X)), sources, targets)).isFalse();
        assertThat(MappingRules.bijection(List.of(new Link(A, X)), sources, targets)).isFalse();
        assertThat(MappingRules.bijection(List.of(new Link(A, X), new Link(A, Y)), sources, targets)).isFalse();
        assertThat(MappingRules.bijection(List.of(new Link(A, X), new Link(B, Z)), sources, targets)).isFalse();
        assertThat(MappingRules.bijection(List.of(new Link(A, X), new Link(C, Y)), sources, targets)).isFalse();
        assertThat(MappingRules.bijection(List.of(), sources, targets)).isFalse();
    }

    @Test
    void manyToOneAllowsSharedAndEmptyTargetsButNeverAMissingOrForeignSource() {
        Set<UUID> sources = Set.of(A, B, C);
        Set<UUID> targets = Set.of(X, Y, Z);
        // several items in one category, two categories left empty
        assertThat(MappingRules.totalManyToOne(List.of(new Link(A, X), new Link(B, X), new Link(C, X)), sources, targets)).isTrue();
        assertThat(MappingRules.totalManyToOne(List.of(new Link(A, X), new Link(B, Y), new Link(C, Z)), sources, targets)).isTrue();
        // what a bijection would also reject must stay legal here, and the reverse never holds
        assertThat(MappingRules.bijection(List.of(new Link(A, X), new Link(B, X), new Link(C, X)), sources, targets)).isFalse();
        assertThat(MappingRules.totalManyToOne(List.of(new Link(A, X), new Link(B, Y)), sources, targets)).isFalse();
        assertThat(MappingRules.totalManyToOne(List.of(new Link(A, X), new Link(A, Y), new Link(B, X), new Link(C, X)),
                sources, targets)).isFalse();
        assertThat(MappingRules.totalManyToOne(List.of(new Link(A, X), new Link(B, X), new Link(C, UUID.randomUUID())),
                sources, targets)).isFalse();
        assertThat(MappingRules.totalManyToOne(List.of(new Link(A, X), new Link(B, X), new Link(UUID.randomUUID(), Y)),
                sources, targets)).isFalse();
        assertThat(MappingRules.totalManyToOne(List.of(), sources, targets)).isFalse();
    }
}

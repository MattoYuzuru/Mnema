package app.mnema.learning.usage;

import java.util.List;
import java.util.Map;

/** The body of {@code POST /api/decks/{deckId}/generation-estimates} ({@code contracts/usage/usage.json#/estimateResponse}). */
public record EstimateView(String rateCardVersion, CreditsView credits, List<LineView> breakdown, BalanceView balance,
                           PercentView percentOfPeriodAllowance, BudgetView budget, boolean canStart,
                           List<BlockView> blockingBuckets, Integer shortfallCredits, List<WarningView> warnings) {

    /** {@code p95} is the sum of rate card weights and what a reservation holds; {@code p50} uses typical weights. */
    public record CreditsView(int p50, int p95) { }

    public record LineView(String operation, int count, int credits) { }

    public record BalanceView(int remainingCredits, String renewsAt) { }

    public record PercentView(int p50, int p95) { }

    /** Set only when the spec asks to spend a share of the remaining budget. */
    public record BudgetView(int percent, int capCredits) { }

    /** One blocking bucket: the members of the {@code USAGE_LIMIT_REACHED} problem. */
    public record BlockView(String bucket, String window, String unit, Long limit, long used, long required,
                            boolean offered, String renewsAt, boolean fitsAfterRenewal, String plan) {
        static BlockView of(UsageLimitReachedException.Block block) {
            return new BlockView(block.bucket().name(), block.window().name(), block.unit().name(), block.limit(),
                    block.used(), block.required(), block.offered(), Wire.time(block.renewsAt()),
                    block.fitsAfterRenewal(), block.plan().name());
        }
    }

    public record WarningView(String code, Map<String, String> sourceRef, List<RangeView> ranges) { }

    static WarningView warning(GenerationSpecInterpreter.Warning warning) {
        return new WarningView(warning.code(), warning.sourceRef(),
                warning.ranges().stream().map(range -> new RangeView(range.start(), range.end())).toList());
    }

    public record RangeView(int start, int end) { }
}

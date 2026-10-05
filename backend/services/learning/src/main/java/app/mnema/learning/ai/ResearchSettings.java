package app.mnema.learning.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code learning.ai.research.*}: web research of a material (AI-18, #299). The providers are {@code learning.ai.providers.yandex-search} and
 * {@code perplexity}, the route is {@code learning.ai.routes.search}; this holds what is neither: the request caps, the result bounds, the
 * time boxes, the Yandex folder and the per-request prices.
 *
 * @param maxRequests the global cap of search requests of one material, whatever the effort (architecture section 14: 15)
 * @param maxResults results kept per material after de-duplication
 * @param resultsPerQuery results asked from a provider per query
 * @param callTimeout the longest one provider request may take
 * @param deadline the budget of one RESEARCH step: the planner call, the searches and the write
 * @param yandexFolderId the Yandex Cloud folder the search is billed to (an identifier, not a secret)
 * @param yandexRegion the Yandex region id of a Russian-language search (225 is Russia)
 * @param yandexRubPerRequest the price of one synchronous Yandex request in roubles incl. VAT (daytime), converted to dollars with
 *                            {@code learning.generation.usd-rub-rate}
 * @param perplexityUsdPerRequest the price of one Perplexity request (up to five queries) in US dollars
 */
@ConfigurationProperties("learning.ai.research")
public record ResearchSettings(@DefaultValue("15") int maxRequests, @DefaultValue("30") int maxResults, @DefaultValue("5") int resultsPerQuery,
                               @DefaultValue("PT10S") Duration callTimeout, @DefaultValue("PT90S") Duration deadline,
                               @DefaultValue("") String yandexFolderId, @DefaultValue("225") String yandexRegion,
                               @DefaultValue("0.488") BigDecimal yandexRubPerRequest, @DefaultValue("0.005") BigDecimal perplexityUsdPerRequest) {
    /** The request caps by effort (architecture section 14): short does no research, {@code AUTO} is bounded like a middle one. */
    private static final int MEDIUM = 2;
    private static final int DETAILED = 6;
    private static final int AUTO = 3;

    @ConstructorBinding
    public ResearchSettings {
        if (maxRequests < 0 || maxRequests > 50 || maxResults < 1 || maxResults > 100 || resultsPerQuery < 1 || resultsPerQuery > 20) {
            throw new IllegalArgumentException("Invalid learning.ai.research limits");
        }
        if (callTimeout == null || callTimeout.isNegative() || callTimeout.isZero() || callTimeout.compareTo(Duration.ofSeconds(60)) > 0
                || deadline == null || deadline.compareTo(Duration.ofSeconds(10)) < 0 || deadline.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Invalid learning.ai.research timeouts");
        }
        yandexFolderId = yandexFolderId == null ? "" : yandexFolderId.strip();
        if (!yandexFolderId.isEmpty() && !yandexFolderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid learning.ai.research.yandex-folder-id");
        }
        if (yandexRegion == null || !yandexRegion.matches("[0-9]{1,6}")) throw new IllegalArgumentException("Invalid learning.ai.research.yandex-region");
        if (yandexRubPerRequest == null || yandexRubPerRequest.signum() < 0 || perplexityUsdPerRequest == null || perplexityUsdPerRequest.signum() < 0) {
            throw new IllegalArgumentException("Invalid learning.ai.research prices");
        }
    }

    /** Defaults, for code that builds the pieces without Spring binding. */
    public static ResearchSettings defaults() {
        return new ResearchSettings(15, 30, 5, Duration.ofSeconds(10), Duration.ofSeconds(90), "", "225", new BigDecimal("0.488"),
                new BigDecimal("0.005"));
    }

    /**
     * How many search requests a material of {@code effort} may make: 0 for {@code SHORT}, 2 for {@code MEDIUM}, 6 for {@code DETAILED}, 3 for
     * {@code AUTO}, each bounded by {@code max}. The usage module prices the hold with this, the research step runs with it.
     */
    public static int cap(String effort, int max) {
        int cap = switch (effort == null ? "" : effort) {
            case "MEDIUM" -> MEDIUM;
            case "DETAILED" -> DETAILED;
            case "AUTO" -> AUTO;
            default -> 0;
        };
        return Math.min(cap, Math.max(0, max));
    }

    public int cap(String effort) { return cap(effort, maxRequests); }
}

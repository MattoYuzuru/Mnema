package app.mnema.learning.usage;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The entitlement source until billing (#79) exists: every account is on {@code learning.usage.entitlements.default-plan}
 * (Free) except the accounts named by {@code learning.usage.entitlements.overrides.<accountUuid>=PRO}, which is how a
 * local owner gets a paid plan. The snapshot is valid until the end of the current calendar month. It is the fallback of
 * {@link InboxEntitlementSource}, the only {@link EntitlementSource} of the application.
 */
@Component
final class ConfigEntitlementSource {
    private static final String OVERRIDES = "learning.usage.entitlements.overrides";

    private final Plan defaultPlan;
    private final Map<UUID, Plan> overrides;
    private final UsageCalendar calendar;

    ConfigEntitlementSource(Environment environment, UsageCalendar calendar,
                            @Value("${learning.usage.entitlements.default-plan:FREE}") Plan defaultPlan) {
        this.calendar = calendar;
        this.defaultPlan = defaultPlan;
        Map<String, Plan> configured = Binder.get(environment)
                .bind(OVERRIDES, Bindable.mapOf(String.class, Plan.class)).orElse(Map.of());
        Map<UUID, Plan> parsed = new HashMap<>();
        configured.forEach((account, plan) -> parsed.put(UuidPolicy.requireEntityId(UUID.fromString(account), "account"), plan));
        this.overrides = Map.copyOf(parsed);
    }

    /** The configured entitlement; {@link InboxEntitlementSource} falls back to it. */
    Entitlement current(UUID owner, Instant now) {
        return new Entitlement(overrides.getOrDefault(owner, defaultPlan), Entitlement.Source.CONFIG,
                calendar.period(now).end());
    }
}

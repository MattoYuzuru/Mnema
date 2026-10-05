package app.mnema.learning.promo;

import app.mnema.learning.platform.api.IdentityUnavailableException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.security.AccountStandings;
import app.mnema.learning.usage.Entitlement;
import app.mnema.learning.usage.EntitlementInbox;
import app.mnema.learning.usage.EntitlementSource;
import app.mnema.learning.usage.Plan;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static app.mnema.learning.promo.PromoRejectedException.Reason;

/**
 * Redemption of a promo code. The order is deliberate: an idempotent replay costs nothing; every other attempt takes a place of the hourly limits
 * first (so guessing is rate limited as hard as using), then the account must have a verified email (Identity, fail closed), then the address
 * must not be a farm of accounts, and only then is the code read, under a row lock, so {@code max_redemptions} is exact however many
 * redemptions race. A tier code publishes an entitlement snapshot to {@link EntitlementInbox} with the same call a payment will use; a discount code
 * stores a pending discount that billing (#79) reads. Nothing here logs a code, an address or an email: ids and counts only.
 */
@Service
public class PromoService {
    private static final Logger log = LoggerFactory.getLogger(PromoService.class);
    private static final ZoneId MESSAGE_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
            .withZone(MESSAGE_ZONE);

    private final PromoRepository repository;
    private final PromoAttempts attempts;
    private final PromoSettings settings;
    private final AccountStandings standings;
    private final EntitlementSource entitlements;
    private final EntitlementInbox inbox;
    private final CommandReceiptService receipts;
    private final UsageClock clock;

    PromoService(PromoRepository repository, PromoAttempts attempts, PromoSettings settings, AccountStandings standings,
                 EntitlementSource entitlements, EntitlementInbox inbox, CommandReceiptService receipts, UsageClock clock) {
        this.repository = repository;
        this.attempts = attempts;
        this.settings = settings;
        this.standings = standings;
        this.entitlements = entitlements;
        this.inbox = inbox;
        this.receipts = receipts;
        this.clock = clock;
    }

    /**
     * Redeems {@code rawCode} for {@code owner}. A repeat of a succeeded command ({@code commandId}) returns the same answer without taking an attempt.
     *
     * @return {@code {type, plan, validUntil, percent?, message}}
     * @throws PromoRejectedException           the rules refuse (see {@link Reason})
     * @throws app.mnema.learning.platform.api.RateLimitedException an hourly window is full
     * @throws IdentityUnavailableException      Identity could not say whether the email is verified
     */
    public JsonNode redeem(UUID owner, Jwt token, UUID commandId, String rawCode, PromoClient client) {
        Optional<String> normalized = PromoCodes.normalize(rawCode);
        CommandIdentity identity = new CommandIdentity(commandId, owner, "promo", "redeem");
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("code", normalized.orElse("invalid"));
        Optional<JsonNode> replay = receipts.replay(identity, payload);
        if (replay.isPresent()) return replay.get();

        attempts.take(owner, client);
        AccountStandings.Standing standing = standings.of(token).orElseThrow(IdentityUnavailableException::new);
        if (!standing.emailVerified()) throw rejected(owner, Reason.NOT_ELIGIBLE);
        if (client.ipHash() != null && repository.otherRedeemersFromIp(client.ipHash(), owner,
                clock.now().minus(settings.velocityWindow)) >= settings.velocityAccounts) {
            throw rejected(owner, Reason.VELOCITY);
        }
        if (normalized.isEmpty()) throw rejected(owner, Reason.INVALID);
        byte[] hash = PromoCodes.hash(normalized.get());
        return receipts.execute(identity, payload, () -> redeemLocked(owner, hash, client));
    }

    private JsonNode redeemLocked(UUID owner, byte[] hash, PromoClient client) {
        Instant now = clock.now();
        PromoRepository.Code code = repository.lockByHash(hash).orElseThrow(() -> rejected(owner, Reason.INVALID));
        if (!code.enabled() || now.isBefore(code.validFrom()) || (code.validUntil() != null && !now.isBefore(code.validUntil()))) {
            throw rejected(owner, Reason.INVALID);
        }
        if (code.oncePerAccount() && repository.redeemedBy(code.codeId(), owner)) throw rejected(owner, Reason.ALREADY_USED);
        if (repository.redemptionCount(code.codeId()) >= code.maxRedemptions()) throw rejected(owner, Reason.EXHAUSTED);
        if (code.type().grantsTier()) {
            Entitlement current = entitlements.current(owner, now);
            // A tier below the one the account already has would change nothing and burn the code.
            if (current.plan().ordinal() > Plan.valueOf(code.plan()).ordinal()) throw rejected(owner, Reason.NOT_ELIGIBLE);
        }
        UUID redemptionId = UUID.randomUUID();
        String snapshotId = code.type().grantsTier() ? "promo:" + redemptionId : null;
        try {
            repository.insertRedemption(redemptionId, code.codeId(), owner, now, client, snapshotId, code.oncePerAccount());
        } catch (DuplicateKeyException failure) {
            throw rejected(owner, Reason.ALREADY_USED);
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("type", code.type().name());
        if (code.type().grantsTier()) {
            Plan plan = Plan.valueOf(code.plan());
            Instant end = code.type() == PromoType.TIER_DAYS ? now.plus(code.days(), ChronoUnit.DAYS)
                    : now.atZone(ZoneOffset.UTC).plusMonths(code.months()).toInstant();
            // The allowance document is the plan's catalog: usage grants it per calendar month from the plan name.
            inbox.accept(new EntitlementInbox.Snapshot(snapshotId, owner, plan, "PROMO", now, end,
                    JsonNodeFactory.instance.objectNode().put("allowances", "catalog").put("plan", plan.name()), end));
            result.put("plan", plan.name()).put("validUntil", wire(end))
                    .put("message", label(plan) + " до " + DAY.format(end) + ", без автопродления.");
        } else {
            repository.upsertDiscount(owner, code.percent(), code.plan(), code.validUntil(), code.codeId(), now);
            result.put("plan", code.plan()).put("validUntil", wire(code.validUntil())).put("percent", code.percent())
                    .put("message", "Скидка " + code.percent() + " % применится к оплате до " + DAY.format(code.validUntil()) + ".");
        }
        log.info("promo redeemed redemption_id={} code_id={} owner_id={} type={}", redemptionId, code.codeId(), owner, code.type());
        return result;
    }

    private PromoRejectedException rejected(UUID owner, Reason reason) {
        log.info("promo rejected owner_id={} reason={}", owner, reason);
        return new PromoRejectedException(reason);
    }

    static String label(Plan plan) {
        return plan.name().charAt(0) + plan.name().substring(1).toLowerCase(Locale.ROOT);
    }

    static String wire(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}

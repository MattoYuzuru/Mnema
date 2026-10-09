package app.mnema.learning.billing;

import app.mnema.learning.platform.api.BillingPlanBelowCurrentException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.PaymentProviderUnavailableException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.promo.PromoDiscounts;
import app.mnema.learning.usage.Entitlement;
import app.mnema.learning.usage.EntitlementSource;
import app.mnema.learning.usage.Plan;
import app.mnema.learning.usage.PlanPrices;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Checkout and the read of an order ({@code contracts/billing}). The price is the catalog's, less the account's pending promo discount for that plan; the
 * browser never sends an amount, and the return URL it comes back on is never trusted: only the bank's {@code GetState} moves an order to {@code PAID}
 * ({@link PaymentStateApplier}).
 *
 * <p>Checkout order of decisions: the mode and the credentials, then the shape of the request, then, in one command receipt (an idempotent retry returns
 * the same order), the hourly limit, the current plan, the price and a reusable open order or a new {@code CREATED} one. {@code Init} runs outside every
 * transaction: a bank that does not answer cannot hold a database connection, and an order that never got a link stays {@code CREATED} so that a retry with
 * the same key asks the bank again.
 */
@Service
class BillingService {
    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    /** An open order is reused only while its link has at least this much left, so a payer is not sent to a form about to expire. */
    private static final Duration REUSE_MARGIN = Duration.ofMinutes(5);
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    private static final int KOPECKS = 100;
    /** How long one caller owns the right to call {@code Init} for an order; a loser is told to retry, and by then the order is {@code PENDING}. */
    private static final Duration INIT_CLAIM = Duration.ofSeconds(30);

    private final BillingRepository repository;
    private final BillingSettings settings;
    private final TBankClient bank;
    private final PaymentStateApplier applier;
    private final CommandReceiptService receipts;
    private final EntitlementSource entitlements;
    private final PlanPrices prices;
    private final PromoDiscounts discounts;
    private final UsageClock clock;
    /** A savepoint around the insert of a discounted order: a unique-index conflict must not abort the checkout's transaction. */
    private final TransactionTemplate savepoint;

    BillingService(BillingRepository repository, BillingSettings settings, TBankClient bank, PaymentStateApplier applier,
                   CommandReceiptService receipts, EntitlementSource entitlements, PlanPrices prices, PromoDiscounts discounts, UsageClock clock,
                   PlatformTransactionManager transactions) {
        this.repository = repository;
        this.settings = settings;
        this.bank = bank;
        this.applier = applier;
        this.receipts = receipts;
        this.entitlements = entitlements;
        this.prices = prices;
        this.discounts = discounts;
        this.clock = clock;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * Starts or resumes the purchase of {@code plan} for one month.
     *
     * @throws app.mnema.learning.platform.api.CapabilityUnavailableException checkout is off for the owner or not configured
     * @throws InvalidRequestException                                       the plan is not purchasable or the period is not {@code MONTH}
     * @throws RateLimitedException                                           too many orders in the last hour
     * @throws BillingPlanBelowCurrentException                               the account already has a higher plan
     * @throws PaymentProviderUnavailableException                            the bank did not answer {@code Init}
     */
    OrderView checkout(UUID owner, UUID commandId, String rawPlan, String rawPeriod) {
        settings.requireAvailable(owner);
        Plan plan = purchasable(rawPlan);
        if (!BillingOrder.PERIOD.equals(rawPeriod)) throw InvalidRequestException.because("period");
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("plan", plan.name()).put("period", BillingOrder.PERIOD);
        CommandIdentity identity = new CommandIdentity(commandId, owner, "billing", "checkout");
        JsonNode receipt = receipts.execute(identity, payload, () -> createOrder(owner, plan));
        UUID orderId = UUID.fromString(receipt.path("orderId").stringValue(null));
        BillingOrder order = load(orderId);
        if (order.status() == OrderStatus.CREATED) order = startPayment(order);
        return OrderView.of(order, clock.now());
    }

    /**
     * The order {@code rawOrderId} of {@code owner}; while it is {@code PENDING} and nobody asked the bank within the refresh interval, the bank is asked now
     * and its answer applied. A bank that does not answer changes nothing: the current state is returned.
     *
     * @throws ResourceNotFoundException the id is malformed, unknown or another account's
     */
    OrderView read(UUID owner, String rawOrderId) {
        UUID orderId = TBankClient.orderId(rawOrderId);
        BillingOrder order = orderId == null ? null : repository.find(orderId).filter(found -> found.owner().equals(owner)).orElse(null);
        if (order == null) throw new ResourceNotFoundException();
        Instant now = clock.now();
        if (order.status() == OrderStatus.PENDING && order.paymentId() != null && settings.configured()
                && repository.claimRefresh(orderId, now, now.minus(settings.refreshInterval))) {
            refresh(order);
            order = load(orderId);
        }
        return OrderView.of(order, clock.now());
    }

    private void refresh(BillingOrder order) {
        try {
            applier.apply(order.orderId(), bank.getState(order.paymentId()), PaymentStateApplier.Trigger.GET_STATE);
        } catch (PaymentProviderException failure) {
            // Already logged by the client; the page shows the state it has and the next read, notification or reconciler run tries again.
        } catch (RuntimeException failure) {
            log.warn("billing order refresh failed order_id={} error_type={}", order.orderId(), failure.getClass().getSimpleName());
        }
    }

    private BillingOrder startPayment(BillingOrder order) {
        Instant now = clock.now();
        // One caller per window opens the bank payment: a concurrent retry or reuse of the same order is told to come back, and then finds it PENDING.
        if (!repository.claimInit(order.orderId(), now, now.minus(INIT_CLAIM))) throw new PaymentProviderUnavailableException();
        TBankClient.InitResult result;
        try {
            result = bank.init(order);
        } catch (PaymentProviderException failure) {
            applier.initFailed(order.orderId(), failure);
            // A refusal opened nothing, so the retry may ask at once; after a timeout or a broken connection the bank may have opened a payment, so the claim stays.
            if (failure.reason() == PaymentProviderException.Reason.REFUSED) repository.releaseInit(order.orderId());
            throw new PaymentProviderUnavailableException();
        }
        applier.initialized(order.orderId(), result);
        return load(order.orderId());
    }

    private BillingOrder load(UUID orderId) {
        return repository.find(orderId).orElseThrow(() -> new IllegalStateException("Billing order vanished"));
    }

    private static Plan purchasable(String raw) {
        if (Plan.PLUS.name().equals(raw)) return Plan.PLUS;
        if (Plan.PRO.name().equals(raw)) return Plan.PRO;
        throw InvalidRequestException.because("plan");
    }

    /** Runs inside the command receipt's transaction. */
    private JsonNode createOrder(UUID owner, Plan plan) {
        repository.lockAccount("checkout", owner);
        Instant now = clock.now();
        if (repository.createdSince(owner, now.minus(RATE_WINDOW)) >= settings.checkoutPerHour) {
            Instant oldest = repository.oldestCreatedSince(owner, now.minus(RATE_WINDOW)).orElse(now);
            throw new RateLimitedException(Math.max(1, Duration.between(now, oldest.plus(RATE_WINDOW)).plusSeconds(1).toSeconds()));
        }
        Entitlement current = entitlements.current(owner, now);
        if (current.plan().ordinal() > plan.ordinal()) throw new BillingPlanBelowCurrentException();
        long listRub = prices.rubPerMonth(plan);
        Optional<PromoDiscounts.Pending> discount = discounts.pending(owner).filter(pending -> pending.plan() == null || pending.plan().equals(plan.name()));
        if (discount.isPresent()) {
            // A discount prices one purchase: while another unfinished order holds it, this one is full price (or is that very order, reused below).
            repository.expireDiscountHolders(owner, discount.get().codeId(), now);
            Optional<BillingOrder> holder = repository.openDiscountHolder(owner, discount.get().codeId(), now);
            if (holder.isPresent() && !reusable(holder.get(), plan, discounted(listRub, discount.get().percent()) * KOPECKS, now)) discount = Optional.empty();
        }
        UUID orderId = open(owner, plan, listRub, discount, now);
        return JsonNodeFactory.instance.objectNode().put("orderId", orderId.toString());
    }

    private static boolean reusable(BillingOrder order, Plan plan, long amount, Instant now) {
        return order.plan() == plan && order.amountKopecks() == amount && order.expiresAt().isAfter(now.plus(REUSE_MARGIN));
    }

    /** The id of the reusable open order of this purchase, or of a new one; a discount that loses a race for its unique index is dropped. */
    private UUID open(UUID owner, Plan plan, long listRub, Optional<PromoDiscounts.Pending> discount, Instant now) {
        long amount = discount.map(pending -> discounted(listRub, pending.percent())).orElse(listRub) * KOPECKS;
        Optional<BillingOrder> reusable = repository.openOrder(owner, plan, amount, now.plus(REUSE_MARGIN));
        if (reusable.isPresent()) return reusable.get().orderId();
        UUID orderId = UuidPolicy.newPortableId();
        BillingOrder order = BillingOrder.created(orderId, owner, plan, amount, listRub * KOPECKS, discount.map(PromoDiscounts.Pending::percent).orElse(null),
                discount.map(PromoDiscounts.Pending::codeId).orElse(null), now, now.plus(settings.paymentTtl));
        try {
            savepoint.executeWithoutResult(status -> repository.insert(order));
        } catch (DuplicateKeyException conflict) {
            if (discount.isEmpty()) throw conflict;
            log.info("billing discount held by another order, priced in full owner_id={}", owner);
            return open(owner, plan, listRub, Optional.empty(), now);
        }
        log.info("billing order created order_id={} owner_id={} plan={}", orderId, owner, plan);
        return orderId;
    }

    /** Whole rubles after the discount, half up. */
    static long discounted(long rub, int percent) {
        return BigDecimal.valueOf(rub).multiply(BigDecimal.valueOf(100 - percent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }
}

package app.mnema.learning.promo;

import app.mnema.learning.admin.AdminAudit;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The owner's console of promo campaigns (OB-402; {@code contracts/usage/promo.json}): create (always disabled), list, the kill switch, and the issuances of a campaign. A campaign is
 * immutable except for {@code enabled}: a new offer is a new campaign, so every issued code keeps the terms it was issued under. At most one campaign of a type is enabled; the
 * check is explicit and the partial unique index {@code promo_campaign_one_enabled} is the backstop for a race. The caller (the controller) has already confirmed the administrator
 * with {@link PromoAdminService#requireAdmin}. Creation is idempotent by command id through the global receipts; the journal ({@link AdminAudit}) records creation and every
 * actual change of {@code enabled}, never a repeat. The issuance list carries the code hint and the dates, never an account id or the plain code.
 */
@Service
public class PromoCampaignService {
    private static final Logger log = LoggerFactory.getLogger(PromoCampaignService.class);
    static final int CAMPAIGN_PAGE = 100;
    static final int ISSUANCE_PAGE = 100;

    /** A creation request, already shaped by the controller; the service validates the combination. */
    record Create(PromoCampaignType type, PromoType offerType, Integer percent, String plan, Integer days) { }

    private final PromoCampaignRepository repository;
    private final CommandReceiptService receipts;
    private final AdminAudit audit;
    private final UsageClock clock;

    PromoCampaignService(PromoCampaignRepository repository, CommandReceiptService receipts, AdminAudit audit, UsageClock clock) {
        this.repository = repository;
        this.receipts = receipts;
        this.audit = audit;
        this.clock = clock;
    }

    /** Creates a disabled campaign; a replay of the same command id and body returns the stored answer and creates nothing. */
    @Transactional
    ObjectNode createCommand(UUID admin, UUID key, JsonNode payload, Create command) {
        validate(command);
        JsonNode saved = receipts.execute(new CommandIdentity(key, admin, "promo.admin", "promo.campaign.create"), payload, () -> {
            UUID id = UUID.randomUUID();
            Instant now = clock.now().truncatedTo(ChronoUnit.SECONDS);
            repository.insert(new PromoCampaignRepository.Campaign(id, command.type(), command.offerType(), command.percent(), command.plan(), command.days(), false, now, admin));
            audit.append(admin, "PROMO_CAMPAIGN_CREATE", id, key);
            log.info("promo campaign created campaign_id={} admin_id={} type={} offer={}", id, admin, command.type(), command.offerType());
            return view(repository.find(id).orElseThrow());
        });
        return (ObjectNode) saved;
    }

    private static void validate(Create command) {
        boolean plusOrPro = "PLUS".equals(command.plan()) || "PRO".equals(command.plan());
        boolean shape = switch (command.offerType()) {
            case TIER_DAYS -> plusOrPro && command.days() != null && command.days() >= 1 && command.days() <= 366 && command.percent() == null;
            case DISCOUNT_PERCENT -> command.percent() != null && command.percent() >= 1 && command.percent() <= 90 && command.days() == null
                    && (command.plan() == null || plusOrPro);
            case TIER_MONTHS -> false;
        };
        if (!shape) throw InvalidRequestException.because("offer");
    }

    @Transactional(readOnly = true)
    ObjectNode list() {
        ArrayNode campaigns = JsonNodeFactory.instance.arrayNode();
        repository.list(CAMPAIGN_PAGE).forEach(entry -> campaigns.add(view(entry)));
        return JsonNodeFactory.instance.objectNode().set("campaigns", campaigns);
    }

    /**
     * The kill switch of the owner. Repeating the current state changes and journals nothing; enabling while another campaign of the type is enabled is
     * {@link PromoCampaignActiveException}. Codes already issued stay valid until their own expiry.
     *
     * @throws ResourceNotFoundException the campaign does not exist
     */
    @Transactional
    ObjectNode setEnabled(UUID admin, UUID campaignId, boolean enabled) {
        PromoCampaignRepository.CampaignWithCounts current = repository.find(campaignId).orElseThrow(ResourceNotFoundException::new);
        if (current.campaign().enabled() == enabled) return view(current);
        if (enabled && repository.otherEnabled(current.campaign().type(), campaignId)) throw new PromoCampaignActiveException();
        boolean changed;
        try {
            changed = repository.setEnabled(campaignId, enabled, clock.now().truncatedTo(ChronoUnit.SECONDS));
        } catch (DuplicateKeyException race) {
            // Two campaigns enabled together: the partial unique index let only one of them through.
            throw new PromoCampaignActiveException();
        }
        if (changed) {
            log.info("promo campaign switched campaign_id={} admin_id={} enabled={}", campaignId, admin, enabled);
            audit.append(admin, enabled ? "PROMO_CAMPAIGN_ENABLE" : "PROMO_CAMPAIGN_DISABLE", campaignId, null);
        }
        return view(repository.find(campaignId).orElseThrow(ResourceNotFoundException::new));
    }

    /**
     * The issuances of a campaign, newest first, 100 a page; {@code after} is the opaque {@code next} of the previous page.
     *
     * @throws ResourceNotFoundException the campaign does not exist
     * @throws InvalidRequestException the cursor is not one this service made
     */
    @Transactional(readOnly = true)
    ObjectNode issuances(UUID campaignId, String after) {
        repository.find(campaignId).orElseThrow(ResourceNotFoundException::new);
        Cursor cursor = after == null ? null : Cursor.parse(after);
        List<PromoCampaignRepository.Issuance> page = repository.issuances(campaignId, cursor == null ? null : cursor.issuedAt(), cursor == null ? null : cursor.codeId(), ISSUANCE_PAGE + 1);
        ArrayNode issuances = JsonNodeFactory.instance.arrayNode();
        page.stream().limit(ISSUANCE_PAGE).forEach(entry -> issuances.addObject().put("codeHint", entry.hint()).put("issuedAt", PromoService.wire(entry.issuedAt()))
                .put("expiresAt", PromoService.wire(entry.expiresAt())).put("redeemedAt", PromoService.wire(entry.redeemedAt())));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("issuances", issuances);
        if (page.size() > ISSUANCE_PAGE) {
            PromoCampaignRepository.Issuance last = page.get(ISSUANCE_PAGE - 1);
            result.put("next", new Cursor(last.issuedAt(), last.codeId()).encode());
        } else {
            result.putNull("next");
        }
        return result;
    }

    private static ObjectNode view(PromoCampaignRepository.CampaignWithCounts entry) {
        PromoCampaignRepository.Campaign campaign = entry.campaign();
        ObjectNode view = JsonNodeFactory.instance.objectNode().put("campaignId", campaign.campaignId().toString()).put("type", campaign.type().name())
                .put("offerType", campaign.offerType().name());
        view.put("percent", campaign.percent());
        view.put("plan", campaign.plan());
        view.put("days", campaign.days());
        return view.put("enabled", campaign.enabled()).put("createdAt", PromoService.wire(campaign.createdAt())).put("issued", entry.issued())
                .put("redeemed", entry.redeemed()).put("issuedLast24h", entry.issuedLast24h());
    }

    /** The last row of a page: the issuance time and the code id make the order total. Opaque to the client (URL-safe Base64 of {@code <instant>|<id>}). */
    private record Cursor(Instant issuedAt, UUID codeId) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((issuedAt + "|" + codeId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor parse(String text) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                Cursor cursor = new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
                if (!cursor.encode().equals(text)) throw new IllegalArgumentException();
                return cursor;
            } catch (IllegalArgumentException | DateTimeParseException failure) {
                throw new InvalidRequestException();
            }
        }
    }
}

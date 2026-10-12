package app.mnema.learning.community;

import app.mnema.learning.admin.AdminAudit;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The owner console's side of the anomaly flags (Community/3, #450; contract {@code contracts/community/catalog.json}, {@code anomalies}): the list and the dismissal. The caller (the
 * controller) has already proved the console owner and the admin client token; the actor is that verified account.
 *
 * <p><b>Dismissal</b> is one transaction with the house receipt: the flag is found by its (current or last known) public code and locked, dismissed once, and ONE
 * {@code admin_audit} row ({@code COMMUNITY_ANOMALY_DISMISS}, resource = the deck) is appended in that transaction. The same command again answers the same without a second row or a
 * second write; another command for a flag that is already dismissed changes and audits nothing. An unknown or malformed code is the opaque 404.
 */
@Service
public class AnomalyAdminService {
    static final int PAGE = 50;
    private static final Pattern CODE = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{10}");

    private final AnomalyRepository repository;
    private final AdminAudit audit;
    private final CommandReceiptService receipts;
    private final Clock clock;

    @Autowired
    AnomalyAdminService(AnomalyRepository repository, AdminAudit audit, CommandReceiptService receipts) {
        this(repository, audit, receipts, Clock.systemUTC());
    }

    AnomalyAdminService(AnomalyRepository repository, AdminAudit audit, CommandReceiptService receipts, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.receipts = receipts;
        this.clock = clock;
    }

    /** One page, open flags first and then dismissed ones, newest detection first; {@code next} is an opaque cursor or null. */
    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(String cursor) {
        AnomalyCursor after = AnomalyCursor.decode(cursor);
        List<AnomalyRepository.Flag> rows = repository.page(after == null ? null : new AnomalyRepository.After(after.dismissed(), after.detectedAt(), after.deckId()), PAGE);
        boolean more = rows.size() > PAGE;
        List<AnomalyRepository.Flag> flags = more ? rows.subList(0, PAGE) : rows;
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        ArrayNode entries = page.putArray("entries");
        for (AnomalyRepository.Flag flag : flags) {
            ObjectNode entry = entries.addObject().put("code", flag.code()).put("title", flag.title()).put("ownerId", flag.ownerId().toString())
                    .put("detectedAt", flag.detectedAt().toString()).put("windowForks", flag.windowForks())
                    .put("newShare", flag.newShare().setScale(2, RoundingMode.HALF_UP).doubleValue());
            if (flag.dismissedAt() == null) entry.putNull("dismissedAt");
            else entry.put("dismissedAt", flag.dismissedAt().toString());
        }
        if (more) {
            AnomalyRepository.Flag last = flags.getLast();
            page.put("next", new AnomalyCursor(last.dismissedAt() != null, last.detectedAt(), last.deckId()).encode());
        } else {
            page.putNull("next");
        }
        return page;
    }

    /**
     * @throws ResourceNotFoundException no flag has this code
     * @throws app.mnema.learning.platform.idempotency.IdempotencyConflictException the command id was used for another code or actor
     */
    @Transactional(timeout = 10)
    public void dismiss(UUID actor, String code, UUID commandId) {
        UuidPolicy.requireEntityId(actor, "actor");
        ObjectNode envelope = JsonNodeFactory.instance.objectNode().put("code", code);
        receipts.execute(new CommandIdentity(commandId, actor, "admin.community", "anomaly.dismiss"), envelope, () -> {
            if (!CODE.matcher(code).matches()) throw new ResourceNotFoundException();
            AnomalyRepository.Flag flag = repository.lockByCode(code).orElseThrow(ResourceNotFoundException::new);
            if (repository.dismiss(flag.deckId(), clock.instant())) audit.append(actor, "COMMUNITY_ANOMALY_DISMISS", flag.deckId(), commandId);
            return JsonNodeFactory.instance.objectNode().put("commandId", commandId.toString());
        });
    }
}

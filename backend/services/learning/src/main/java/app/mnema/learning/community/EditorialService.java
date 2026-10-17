package app.mnema.learning.community;

import app.mnema.learning.admin.AdminAudit;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The owner console's side of the editorial shelf «Выбор Мнемы» (Community/7, #454; contract {@code contracts/community/catalog.json}, {@code editorial}): the list and its replacement. The
 * caller (the controller) has already proved the console owner and the admin client token; the actor is that verified account.
 *
 * <p><b>The command</b> is a PUT of the WHOLE ordered list of public codes (at most {@value #MAX}, no duplicates). One transaction with the house receipt (scope {@code admin.community},
 * type {@code editorial.update}, the payload is the list): the shelf row is locked, the version is compared with {@code If-Match} (412 when stale; the same command again replays its
 * stored answer first, before the version is looked at), every code must be a PUBLIC published deck that is not deleted (otherwise 400 with the offending codes in the {@code codes}
 * member; a code of an unknown shape is a plain 400, never echoed), and the list is rewritten in place. A command that leaves the list exactly as it is changes nothing: no new version,
 * no audit row. An effective change appends ONE {@code admin_audit} row, {@code COMMUNITY_EDITORIAL_UPDATE}, in the same transaction (resource: the shelf; command: this command id).
 * The rows of decks that stay in the list keep their {@code added_at} and {@code added_by}.
 */
@Service
public class EditorialService {
    static final int MAX = 24;
    private static final Pattern CODE = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{10}");

    private final EditorialRepository repository;
    private final AdminAudit audit;
    private final CommandReceiptService receipts;
    private final Clock clock;

    @Autowired
    EditorialService(EditorialRepository repository, AdminAudit audit, CommandReceiptService receipts) {
        this(repository, audit, receipts, Clock.systemUTC());
    }

    EditorialService(EditorialRepository repository, AdminAudit audit, CommandReceiptService receipts, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.receipts = receipts;
        this.clock = clock;
    }

    /** The list with its version. */
    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode state() {
        return document(repository.version(), repository.entries());
    }

    /**
     * Replaces the list.
     *
     * @param expectedVersion the {@code If-Match} version
     * @param codes the whole new list, in order
     * @throws VersionConflictException the list changed after {@code expectedVersion} was read
     * @throws InvalidRequestException a code is not a PUBLIC published deck (member {@code codes}), is repeated or is malformed, or the list is longer than {@value #MAX}
     * @throws app.mnema.learning.platform.idempotency.IdempotencyConflictException the command id was used for another list or actor
     */
    @Transactional(timeout = 10)
    public JsonNode replace(UUID actor, UUID commandId, long expectedVersion, List<String> codes) {
        UuidPolicy.requireEntityId(actor, "actor");
        if (codes.size() > MAX) throw new InvalidRequestException();
        Set<String> seen = new HashSet<>();
        for (String code : codes) {
            if (!CODE.matcher(code).matches() || !seen.add(code)) throw new InvalidRequestException();
        }
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        ArrayNode list = envelope.putArray("codes");
        codes.forEach(list::add);
        return receipts.execute(new CommandIdentity(commandId, actor, "admin.community", "editorial.update"), envelope, () -> {
            if (repository.lockVersion() != expectedVersion) throw new VersionConflictException();
            Map<String, EditorialRepository.Target> targets = new HashMap<>();
            repository.targets(codes).forEach(target -> targets.put(target.code(), target));
            List<String> unknown = codes.stream().filter(code -> !targets.containsKey(code)).toList();
            if (!unknown.isEmpty()) throw new InvalidRequestException(ProblemExtension.builder().put("codes", unknown).build());
            List<EditorialRepository.Entry> current = repository.entries();
            List<UUID> wanted = codes.stream().map(code -> targets.get(code).deckId()).toList();
            if (current.stream().map(EditorialRepository.Entry::deckId).toList().equals(wanted)) return document(expectedVersion, current);
            Instant now = clock.instant();
            Map<UUID, EditorialRepository.Entry> kept = new HashMap<>();
            current.forEach(entry -> kept.put(entry.deckId(), entry));
            repository.clear();
            for (int index = 0; index < wanted.size(); index++) {
                EditorialRepository.Entry before = kept.get(wanted.get(index));
                repository.insert(wanted.get(index), index + 1, before == null ? now : before.addedAt(), before == null ? actor : before.addedBy());
            }
            repository.bump(now);
            audit.append(actor, "COMMUNITY_EDITORIAL_UPDATE", EditorialRepository.SHELF, commandId);
            return document(expectedVersion + 1, repository.entries());
        });
    }

    private static ObjectNode document(long version, List<EditorialRepository.Entry> entries) {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.put("rowVersion", Long.toString(version));
        ArrayNode list = document.putArray("entries");
        for (EditorialRepository.Entry entry : entries) {
            list.addObject().put("code", entry.code()).put("title", entry.title()).put("listed", entry.listed()).put("addedAt", CatalogCards.seconds(entry.addedAt()));
        }
        return document;
    }
}

package app.mnema.learning.study.progress;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class StudyProgressService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final String CURSOR_PREFIX = "study-progress-v1:";

    private final StudyProgressRepository repository;

    public StudyProgressService(StudyProgressRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode read(UUID actor, UUID deck, Integer requestedLimit, String cursor) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        if (!repository.ownsDeck(actor, deck)) throw new ResourceNotFoundException();
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) throw new InvalidRequestException();
        UUID after = decode(cursor);
        Instant asOf = repository.now();
        List<StudyProgressRepository.Material> rows = repository.page(actor, deck, after, limit + 1, asOf);
        boolean more = rows.size() > limit;
        List<StudyProgressRepository.Material> page = more ? rows.subList(0, limit) : rows;

        ObjectNode response = JsonNodeFactory.instance.objectNode().put("asOf", asOf.toString());
        var items = response.putArray("items");
        page.forEach(material -> {
            ObjectNode item = items.addObject().put("memberKey", material.memberKey().toString())
                    .put("itemRevisionId", material.itemRevisionId().toString())
                    .put("state", state(material));
            item.putObject("objectiveCoverage").put("enabled", material.enabled())
                    .put("introduced", material.introduced()).put("assessed", material.assessed());
            nullable(item, "lastAssessedAt", material.lastAssessedAt());
            nullable(item, "nextDue", material.nextDue());
        });
        if (more) response.put("nextCursor", encode(page.getLast().memberKey()));
        else response.putNull("nextCursor");
        return response;
    }

    private static String state(StudyProgressRepository.Material material) {
        if (material.enabled() == 0 || material.introduced() == 0) return "NOT_STARTED";
        if (material.due()) return "DUE";
        if (material.introduced() < material.enabled() || material.assessed() < material.introduced()
                || !material.allOnTrack()) return "LEARNING";
        return "ON_TRACK";
    }

    private static void nullable(ObjectNode target, String name, Instant value) {
        if (value == null) target.putNull(name); else target.put(name, value.toString());
    }

    private static String encode(UUID memberKey) {
        String value = CURSOR_PREFIX + memberKey;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static UUID decode(String cursor) {
        if (cursor == null) return null;
        if (cursor.isBlank() || cursor.length() > 128) throw new InvalidRequestException();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!decoded.startsWith(CURSOR_PREFIX)) throw new InvalidRequestException();
            UUID value = UuidPolicy.requireEntityId(UUID.fromString(decoded.substring(CURSOR_PREFIX.length())), "cursor");
            if (!decoded.equals(CURSOR_PREFIX + value)) throw new InvalidRequestException();
            return value;
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }
}

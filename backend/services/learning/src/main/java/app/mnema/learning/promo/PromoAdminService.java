package app.mnema.learning.promo;

import app.mnema.learning.platform.api.AccessForbiddenException;
import app.mnema.learning.platform.api.IdentityUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.security.AccountStandings;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creating, listing (200 per page, {@code ?after=} the last code id of the previous page) and switching off promo codes. The caller must be an administrator in Identity (the caller's own bearer, cached for at most a
 * minute; when Identity cannot say, the request is refused). The plain code of a created code is in the response of the creation and nowhere else.
 */
@Service
public class PromoAdminService {
    private static final Logger log = LoggerFactory.getLogger(PromoAdminService.class);
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.:/@#+-]{0,39}");
    private static final int GENERATION_ATTEMPTS = 5;
    static final int PAGE_SIZE = 200;

    /** A creation request, already shaped; {@code vanity} is a code the admin chose (normalized on use), else one is generated. */
    public record Create(PromoType type, String plan, Integer days, Integer months, Integer percent, Instant validFrom,
                         Instant validUntil, int maxRedemptions, boolean oncePerAccount, String channel, String vanity) { }

    private final PromoRepository repository;
    private final AccountStandings standings;
    private final UsageClock clock;
    private final PromoSettings settings;

    PromoAdminService(PromoRepository repository, AccountStandings standings, UsageClock clock, PromoSettings settings) {
        this.repository = repository;
        this.standings = standings;
        this.clock = clock;
        this.settings = settings;
    }

    /** @throws AccessForbiddenException the caller is not an administrator; {@link IdentityUnavailableException} Identity did not answer */
    void requireAdmin(Jwt token) {
        AccountStandings.Standing standing = standings.of(token).orElseThrow(IdentityUnavailableException::new);
        if (!standing.admin()) throw new AccessForbiddenException();
    }

    @Transactional
    public ObjectNode create(UUID admin, Create command) {
        settings.requireAvailable();
        Instant now = clock.now().truncatedTo(ChronoUnit.SECONDS);
        validate(command);
        Instant from = command.validFrom() == null ? now : command.validFrom();
        if (command.validUntil() != null && !command.validUntil().isAfter(from)) throw InvalidRequestException.because("period");
        String normalized = null;
        PromoRepository.Code row = null;
        for (int attempt = 0; attempt < (command.vanity() == null ? GENERATION_ATTEMPTS : 1); attempt++) {
            normalized = command.vanity() == null ? PromoCodes.generate() : PromoCodes.normalize(command.vanity())
                    .orElseThrow(() -> InvalidRequestException.because("code"));
            row = new PromoRepository.Code(UUID.randomUUID(), PromoCodes.hint(normalized), command.type(), command.plan(),
                    command.days(), command.months(), command.percent(), from, command.validUntil(), command.maxRedemptions(),
                    command.oncePerAccount(), command.channel(), true, now, admin);
            if (repository.insertCode(row, PromoCodes.hash(settings.hashSecret, normalized))) break;
            row = null;
        }
        if (row == null) throw InvalidRequestException.because("code_taken");
        log.info("promo code created code_id={} admin_id={} type={} max_redemptions={}", row.codeId(), admin, row.type(), row.maxRedemptions());
        ObjectNode view = view(row, 0);
        view.put("code", PromoCodes.display(normalized));
        return view;
    }

    private static void validate(Create command) {
        boolean tier = command.type().grantsTier();
        boolean plusOrPro = "PLUS".equals(command.plan()) || "PRO".equals(command.plan());
        if (tier ? !plusOrPro : (command.plan() != null && !plusOrPro)) throw InvalidRequestException.because("plan");
        boolean shape = switch (command.type()) {
            case TIER_DAYS -> command.days() != null && command.days() >= 1 && command.days() <= 366
                    && command.months() == null && command.percent() == null;
            case TIER_MONTHS -> command.months() != null && command.months() >= 1 && command.months() <= 24
                    && command.days() == null && command.percent() == null;
            case DISCOUNT_PERCENT -> command.percent() != null && command.percent() >= 1 && command.percent() <= 90
                    && command.days() == null && command.months() == null && command.validUntil() != null;
        };
        if (!shape) throw InvalidRequestException.because("amount");
        if (command.maxRedemptions() < 1 || command.maxRedemptions() > 1_000_000) throw InvalidRequestException.because("maxRedemptions");
        if (command.channel() != null && !CHANNEL.matcher(command.channel()).matches()) throw InvalidRequestException.because("channel");
    }

    @Transactional(readOnly = true)
    public ObjectNode list(UUID after) {
        var page = repository.list(after, PAGE_SIZE + 1);
        ArrayNode codes = JsonNodeFactory.instance.arrayNode();
        page.stream().limit(PAGE_SIZE).forEach(entry -> codes.add(view(entry.code(), entry.redemptions())));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("codes", codes);
        // The cursor of the next page: the id of the last code of this one, null on the last page.
        if (page.size() > PAGE_SIZE) result.put("next", page.get(PAGE_SIZE - 1).code().codeId().toString());
        else result.putNull("next");
        return result;
    }

    /** Switches a code on or off: the kill switch. Redemptions that already happened keep their entitlement. */
    @Transactional
    public ObjectNode setEnabled(UUID admin, UUID codeId, boolean enabled) {
        if (!repository.setEnabled(codeId, enabled)) throw new ResourceNotFoundException();
        log.info("promo code switched code_id={} admin_id={} enabled={}", codeId, admin, enabled);
        var entry = repository.find(codeId).orElseThrow(ResourceNotFoundException::new);
        return view(entry.code(), entry.redemptions());
    }

    private static ObjectNode view(PromoRepository.Code code, long redemptions) {
        ObjectNode view = JsonNodeFactory.instance.objectNode().put("codeId", code.codeId().toString())
                .put("hint", code.hint()).put("type", code.type().name());
        view.put("plan", code.plan());
        view.put("days", code.days());
        view.put("months", code.months());
        view.put("percent", code.percent());
        view.put("validFrom", PromoService.wire(code.validFrom())).put("validUntil", PromoService.wire(code.validUntil()))
                .put("maxRedemptions", code.maxRedemptions()).put("oncePerAccount", code.oncePerAccount())
                .put("channel", code.channel()).put("enabled", code.enabled()).put("redemptions", redemptions)
                .put("createdAt", PromoService.wire(code.createdAt()));
        return view;
    }
}

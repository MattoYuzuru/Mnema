package app.mnema.learning.onboarding;

import app.mnema.learning.promo.PersonalPromoCodes;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The reward of the «Путь Mnema» in the view of an account (OB-402, ADR-ONB-005; {@code contracts/onboarding/README.md} «Path reward»): reads it and, on the read that
 * carries the right signals, asks {@link RewardIssuer} to issue it.
 *
 * <p><b>Why the issuing follows the read.</b> The last milestone is often reached in a worker (AI grading, speech) that has no request signals, and a new account typically
 * reaches 100 % before it is 3 days old, so deciding in the milestone's own transaction would refuse the reward for ever. The signal that makes an account trusted
 * ({@code mnema_trusted}: verified e-mail and at least 3 days old) arrives with {@code GET /api/onboarding}, and the client re-reads that view on every {@code PATH_MILESTONE}
 * notification, so the read follows the last milestone; an untrusted or unknown account simply is not issued yet and is asked again by a later read. A replayed tour never issues
 * again (the key of {@code onboarding_reward} is the account), and there is no other path to a reward.
 *
 * <p>The cost: with the reward switched off nothing is read. On, an account with a reward costs one primary-key join per view; an account without one costs the same plus, only while
 * it has every visible milestone done and is trusted, one issuing transaction ({@link RewardIssuer}) per read until it is issued.
 */
@Service
class PathReward {
    private final RewardSettings settings;
    private final RewardRepository repository;
    private final RewardIssuer issuer;
    private final PersonalPromoCodes codes;

    PathReward(RewardSettings settings, RewardRepository repository, RewardIssuer issuer, PersonalPromoCodes codes) {
        this.settings = settings;
        this.repository = repository;
        this.issuer = issuer;
        this.codes = codes;
    }

    /**
     * The {@code reward} of the account's view: null while the reward is switched off or the account has none.
     *
     * @param path the account's path as the view shows it: the reward is issued only when every visible milestone is done
     * @param trusted Identity's {@code mnema_trusted} of this request when the caller is the read of the view; {@code null} for every other caller (a command's answer, an
     *                old Identity), which never issues and only shows an existing reward
     */
    RewardState state(UUID account, PathState path, Boolean trusted) {
        if (!settings.enabled()) return null;
        Optional<RewardRepository.Row> found = repository.find(account);
        if (found.isEmpty() && Boolean.TRUE.equals(trusted) && path.total() >= 1 && path.done() == path.total()) {
            issuer.issue(account);
            found = repository.find(account);
        }
        return found.map(this::of).orElse(null);
    }

    private RewardState of(RewardRepository.Row row) {
        RewardState.State state = row.redeemed() ? RewardState.State.REDEEMED : row.past() ? RewardState.State.EXPIRED : RewardState.State.ACTIVE;
        String code = state == RewardState.State.ACTIVE ? codes.displayCode(row.promoCodeId(), row.codeHash()).orElse(null) : null;
        // An active code that can no longer be derived (the promo secret was rotated, or a development process drew another random one) cannot be redeemed: show no reward.
        if (state == RewardState.State.ACTIVE && code == null) return null;
        return new RewardState(state, code, new RewardState.Offer(row.offerType(), row.percent(), row.plan(), row.days()), row.issuedAt(), row.expiresAt());
    }
}

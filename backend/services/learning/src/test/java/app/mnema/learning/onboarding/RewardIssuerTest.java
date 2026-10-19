package app.mnema.learning.onboarding;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import app.mnema.learning.promo.PersonalPromoCodes;
import app.mnema.learning.promo.PromoCampaignType;
import app.mnema.learning.promo.PromoType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The decisions of {@link RewardIssuer} and the order of its writes, with every collaborator replaced (the real thing is {@code PathRewardIntegrationTest}). */
class RewardIssuerTest {
    private static final Instant ISSUED = Instant.parse("2026-10-22T18:41:00Z");
    private final UUID account = UUID.randomUUID();
    private final UUID campaignId = UUID.randomUUID();
    private final RewardRepository repository = mock(RewardRepository.class);
    private final PersonalPromoCodes codes = mock(PersonalPromoCodes.class);
    private final MilestoneRepository milestones = mock(MilestoneRepository.class);
    private final PathVisibility visibility = mock(PathVisibility.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private RewardIssuer issuer;

    @BeforeEach
    void world() {
        issuer = new RewardIssuer(repository, new RewardSettings(true, 2), codes, milestones, visibility, publisher, meters, transactions);
        when(codes.available()).thenReturn(true);
        PersonalPromoCodes.Campaign enabled = new PersonalPromoCodes.Campaign(campaignId, PromoType.DISCOUNT_PERCENT, 10, null, null, UUID.randomUUID());
        when(codes.enabledCampaign(any())).thenReturn(Optional.of(enabled));
        when(codes.lockEnabledCampaign(any())).thenReturn(Optional.of(enabled));
        when(visibility.hidden()).thenReturn(EnumSet.of(MilestoneKey.AI_EXPLANATION));
        when(milestones.all(account)).thenReturn(everythingVisible());
        when(repository.issuedInLastDay(campaignId)).thenReturn(0L);
        when(repository.now()).thenReturn(ISSUED);
    }

    private static Map<MilestoneKey, MilestoneRepository.Row> everythingVisible() {
        Map<MilestoneKey, MilestoneRepository.Row> records = new EnumMap<>(MilestoneKey.class);
        for (MilestoneKey key : MilestoneKey.values()) records.put(key, new MilestoneRepository.Row(key.target(), ISSUED.minusSeconds(60)));
        return records;
    }

    private double count(String outcome) {
        return meters.counter("mnema_path_reward_total", "outcome", outcome).count();
    }

    @Test
    void theCodeTheRewardAndTheNotificationAreWrittenInThatOrderAndTheOfferLastsSevenDays() {
        UUID code = UUID.randomUUID();
        when(codes.issue(any(), any(), any(), any())).thenReturn(code);

        issuer.issue(account);

        Instant expires = ISSUED.plusSeconds(7 * 24 * 3600);
        InOrder order = inOrder(repository, codes, publisher);
        order.verify(repository).lockAccount(account);
        // the campaign is read again under its row lock before its advisory lock is taken
        order.verify(codes).lockEnabledCampaign(PromoCampaignType.PATH_REWARD);
        order.verify(repository).lockCampaign(campaignId);
        order.verify(codes).issue(any(), org.mockito.ArgumentMatchers.eq(account), org.mockito.ArgumentMatchers.eq(ISSUED), org.mockito.ArgumentMatchers.eq(expires));
        order.verify(repository).insert(account, campaignId, code, ISSUED, expires);
        order.verify(publisher).publish(account, NotificationKind.PATH_REWARD_READY, "path:reward", Map.of("expiresAt", expires), NotificationRoute.PATH);
        assertThat(count("issued")).isEqualTo(1.0);
        verify(transactions).commit(any());
    }

    @Test
    void anExistingRewardIsLeftAloneAndCountsNothing() {
        when(repository.exists(account)).thenReturn(true);

        issuer.issue(account);

        verify(codes, never()).issue(any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any(), any());
        for (String outcome : new String[] {"issued", "no_campaign", "not_complete", "limited", "unavailable", "failed"}) assertThat(count(outcome)).as(outcome).isZero();
    }

    @Test
    void eachRefusalIsCountedUnderItsOwnOutcomeAndWritesNothing() {
        when(codes.lockEnabledCampaign(any())).thenReturn(Optional.empty());
        issuer.issue(account);
        assertThat(count("no_campaign")).isEqualTo(1.0);

        when(codes.lockEnabledCampaign(any())).thenReturn(Optional.of(new PersonalPromoCodes.Campaign(campaignId, PromoType.TIER_DAYS, null, "PLUS", 30, UUID.randomUUID())));
        Map<MilestoneKey, MilestoneRepository.Row> partial = everythingVisible();
        partial.put(MilestoneKey.OWN_EXERCISES, new MilestoneRepository.Row(2, null));
        when(milestones.all(account)).thenReturn(partial);
        issuer.issue(account);
        assertThat(count("not_complete")).isEqualTo(1.0);

        // every milestone hidden is not a path to complete
        when(visibility.hidden()).thenReturn(EnumSet.allOf(MilestoneKey.class));
        when(milestones.all(account)).thenReturn(everythingVisible());
        issuer.issue(account);
        assertThat(count("not_complete")).isEqualTo(2.0);

        when(visibility.hidden()).thenReturn(EnumSet.noneOf(MilestoneKey.class));
        when(repository.issuedInLastDay(campaignId)).thenReturn(2L);
        issuer.issue(account);
        assertThat(count("limited")).isEqualTo(1.0);

        verify(codes, never()).issue(any(), any(), any(), any());
        verify(repository, never()).insert(any(), any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any(), any());
        assertThat(count("issued")).isZero();
    }

    @Test
    void withoutAUsablePromoSecretNothingIsIssuedAndTheOutcomeIsCountedEveryTime() {
        when(codes.available()).thenReturn(false);

        issuer.issue(account);
        issuer.issue(account);

        assertThat(count("unavailable")).isEqualTo(2.0);
        verify(codes, never()).lockEnabledCampaign(any());
        verify(codes, never()).issue(any(), any(), any(), any());
    }

    @Test
    void aFailureIsCountedRolledBackAndNeverReachesTheCaller() {
        when(codes.issue(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new IllegalStateException("account " + account + " is broken")).when(publisher).publish(any(), any(), any(), any(), any());

        assertThatCode(() -> issuer.issue(account)).doesNotThrowAnyException();

        assertThat(count("failed")).isEqualTo(1.0);
        assertThat(count("issued")).isZero();
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    // ---- the check before the transaction ----

    @Test
    void anAccountThatIsNotCompleteIsPassedOverSilentlyWithoutATransactionOrACount() {
        Map<MilestoneKey, MilestoneRepository.Row> partial = everythingVisible();
        partial.put(MilestoneKey.OWN_EXERCISES, new MilestoneRepository.Row(2, null));
        when(milestones.all(account)).thenReturn(partial);

        assertThat(issuer.issueIfDue(account)).isFalse();

        verify(transactions, never()).getTransaction(any());
        verify(codes, never()).enabledCampaign(any());
        for (String outcome : new String[] {"issued", "no_campaign", "not_complete", "limited", "unavailable", "failed"}) assertThat(count(outcome)).as(outcome).isZero();
    }

    @Test
    void theRefusalsAboutThePlatformAreCountedByTheCheckAndNeverOpenATransactionOrTakeALock() {
        when(codes.enabledCampaign(any())).thenReturn(Optional.empty());
        assertThat(issuer.issueIfDue(account)).isFalse();
        assertThat(count("no_campaign")).isEqualTo(1.0);

        when(codes.enabledCampaign(any())).thenReturn(Optional.of(new PersonalPromoCodes.Campaign(campaignId, PromoType.DISCOUNT_PERCENT, 10, null, null, UUID.randomUUID())));
        when(repository.issuedInLastDay(campaignId)).thenReturn(2L);
        assertThat(issuer.issueIfDue(account)).isFalse();
        assertThat(count("limited")).isEqualTo(1.0);

        when(codes.available()).thenReturn(false);
        assertThat(issuer.issueIfDue(account)).isFalse();
        assertThat(count("unavailable")).isEqualTo(1.0);

        verify(transactions, never()).getTransaction(any());
        verify(repository, never()).lockAccount(any());
        verify(repository, never()).lockCampaign(any());
    }

    @Test
    void anAccountThatPassesTheCheckIsIssuedInATransactionOfItsOwn() {
        when(codes.issue(any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        assertThat(issuer.issueIfDue(account)).isTrue();

        assertThat(count("issued")).isEqualTo(1.0);
        verify(transactions).commit(any());
    }

    @Test
    void anExistingRewardFoundUnderTheLockIsReportedAndCountsNothing() {
        when(repository.exists(account)).thenReturn(true);

        assertThat(issuer.issueIfDue(account)).isTrue();

        verify(codes, never()).issue(any(), any(), any(), any());
        assertThat(count("issued")).isZero();
    }

    @Test
    void aFailureOfTheCheckIsCountedAndNeverReachesTheCaller() {
        when(milestones.all(account)).thenThrow(new IllegalStateException("account " + account + " is broken"));

        assertThatCode(() -> assertThat(issuer.issueIfDue(account)).isFalse()).doesNotThrowAnyException();

        assertThat(count("failed")).isEqualTo(1.0);
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void aCampaignThatTheOwnerSwitchedOffAfterTheCheckIssuesNothing() {
        // the unlocked read of the check still saw it enabled; under the row lock it is gone
        when(codes.lockEnabledCampaign(any())).thenReturn(Optional.empty());

        assertThat(issuer.issueIfDue(account)).isFalse();

        assertThat(count("no_campaign")).isEqualTo(1.0);
        verify(repository, never()).lockCampaign(any());
        verify(codes, never()).issue(any(), any(), any(), any());
        verify(publisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void theIssuerRefusesToRunInsideATransactionSoItNeverHoldsTwoConnections() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> issuer.issueIfDue(account)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> issuer.issue(account)).isInstanceOf(IllegalStateException.class);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(transactions, never()).getTransaction(any());
        verify(milestones, never()).all(any());
    }

    @Test
    void theSettingsRefuseALimitOutsideOneToTenThousand() {
        assertThat(new RewardSettings(false, 1).dailyLimit()).isEqualTo(1);
        assertThat(new RewardSettings(true, RewardSettings.MAX_DAILY_LIMIT).dailyLimit()).isEqualTo(10_000);
        for (int bad : new int[] {0, -1, 10_001, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> new RewardSettings(true, bad)).as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("daily-limit");
        }
        // a bad limit stops startup even with the reward switched off: a typo must not wait for the day the flag is turned on
        assertThatThrownBy(() -> new RewardSettings(false, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

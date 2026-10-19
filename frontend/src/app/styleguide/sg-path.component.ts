import { ChangeDetectionStrategy, Component, ViewEncapsulation, inject, signal } from '@angular/core';

import { ToastService } from '../core/notifications/toast.service';
import { presentNotification } from '../core/notifications/notification-presenter';
import { onboardingText } from '../core/onboarding/onboarding-texts';
import { PathHint } from '../core/onboarding/path-hint.service';
import { TourAnchorDirective } from '../core/onboarding/tour-anchor.directive';
import { PathChapterComponent } from '../shared/path-chapter.component';
import { PathHintCardComponent } from '../shared/path-hint-card.component';
import { PathMilestoneComponent } from '../shared/path-milestone.component';
import { QuestChooserComponent } from '../shared/quest-chooser.component';
import { TourCardComponent } from '../shared/tour-card.component';
import { TourPauseComponent } from '../shared/tour-pause.component';
import { QuestDeckOption, QuestNotice } from '../core/onboarding/quest.models';
import { questDefinition } from '../features/onboarding/quest-definitions';
import { PathProgressComponent } from '../shared/path-progress.component';
import { RhythmWeekComponent } from '../shared/rhythm-week.component';
import { RhythmSummaryComponent } from '../features/onboarding/rhythm-summary.component';
import { NightSkyComponent } from '../shared/night-sky.component';
import { RewardCardComponent } from '../shared/reward-card.component';
import { RewardCodeComponent } from '../shared/reward-code.component';
import { rewardGift, rewardPlansQuery } from '../core/onboarding/reward-format';
import { PathReward, Rhythm } from '../core/onboarding/onboarding.models';
import { daysLabel } from '../core/onboarding/rhythm-format';
import { SgSpecimenComponent } from './sg-specimen.component';

/** The Monday of the weeks of the rhythm specimens. */
const WEEK = '2026-10-19';

/** «Путь Mnema»: the milestone in every state, the chapter and the bar, the in-place hint and the toast of a milestone; the week of the rhythm; the reward of the finished path. */
@Component({
    selector: 'app-sg-path',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, PathMilestoneComponent, PathChapterComponent, PathProgressComponent, PathHintCardComponent, TourAnchorDirective, QuestChooserComponent,
        TourCardComponent, TourPauseComponent, RhythmWeekComponent, RhythmSummaryComponent, RewardCardComponent, RewardCodeComponent, NightSkyComponent],
    templateUrl: './sg-path.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgPathComponent {
    private readonly toasts = inject(ToastService);
    private readonly hint = inject(PathHint);
    private toastCounter = 0;

    protected readonly total = onboardingText('path.total', { done: 5, total: 12 });
    protected readonly title = onboardingText('path.title');
    protected readonly chapters = [
        { name: onboardingText('path.chapter.start'), done: 3, total: 3, open: false },
        { name: onboardingText('path.chapter.materials'), done: 1, total: 3, open: true }
    ];
    protected readonly hintCard = { title: onboardingText('m.CAPTURE_NOTE'), text: onboardingText('hint.capture') };
    protected readonly shown = signal(false);

    protected readonly questTitle = onboardingText('m.VOICE_ANSWER');
    protected readonly questDecks: readonly QuestDeckOption[] = [
        { deckId: 'sg-1', title: 'Японский для начинающих', sample: false }, { deckId: 'sg-2', title: 'Физика: механика', sample: false }, { deckId: 'sg-3', title: 'Учебная колода', sample: true }
    ];
    protected readonly questSelected = signal('sg-1');
    protected readonly questNotice: QuestNotice = {
        text: onboardingText('quest.blocked.budget.sample', { n: 2 }),
        actions: [{ kind: 'sample', label: onboardingText('quest.sample.use'), deckId: 'sg-3' }, { kind: 'link', label: onboardingText('ai.plans'), commands: ['/plans'] }]
    };
    /** The screen of the voice quest about the microphone: the quest's own step, drawn by the ordinary card. */
    protected readonly micStep = questDefinition('QUEST_VOICE', { deckId: 'sg', sample: false, memberKey: null }).steps[0];
    protected readonly leaveLabel = onboardingText('quest.leave');

    /** The states of the week of the rhythm (OB-401): no session yet, some days, a week that met its target, today at either end of the week. */
    protected readonly weekStart = WEEK;
    protected readonly weeks = {
        empty: { today: '2026-10-21', days: [] as readonly string[] },
        partial: { today: '2026-10-23', days: ['2026-10-20', '2026-10-22', '2026-10-23'] as readonly string[] },
        met: { today: '2026-10-23', days: ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23'] as readonly string[] },
        first: { today: '2026-10-19', days: ['2026-10-19'] as readonly string[] },
        last: { today: '2026-10-25', days: ['2026-10-20', '2026-10-25'] as readonly string[] }
    } as const;
    protected readonly summaryRhythm: Rhythm = { weekStart: WEEK, ...this.weeks.partial, target: 5, weeksMet: 2 };

    /** The reward of the finished path (OB-402) in the states its card draws: a discount, a discount for one plan, a tier, redeemed, expired. */
    private static readonly REWARD: PathReward = {
        state: 'ACTIVE', code: 'K7PM-W2QX-H9RT', offer: { type: 'DISCOUNT_PERCENT', percent: 10, plan: null, days: null },
        issuedAt: '2026-10-22T18:41:00Z', expiresAt: '2026-10-29T18:41:00Z'
    };
    protected readonly rewards = {
        percent: SgPathComponent.REWARD,
        plan: { ...SgPathComponent.REWARD, offer: { type: 'DISCOUNT_PERCENT', percent: 15, plan: 'PRO', days: null } } as PathReward,
        tier: { ...SgPathComponent.REWARD, offer: { type: 'TIER_DAYS', percent: null, plan: 'PLUS', days: 30 } } as PathReward,
        redeemed: { ...SgPathComponent.REWARD, state: 'REDEEMED', code: null } as PathReward,
        expired: { ...SgPathComponent.REWARD, state: 'EXPIRED', code: null } as PathReward
    } as const;
    protected readonly rewardNight = {
        eyebrow: onboardingText('reward.eyebrow'), title: onboardingText('reward.title'), gift: rewardGift(SgPathComponent.REWARD), terms: onboardingText('reward.terms'),
        action: onboardingText('reward.action'), later: onboardingText('reward.later'), query: rewardPlansQuery(SgPathComponent.REWARD.code ?? '')
    } as const;

    protected showRewardToast(): void {
        const presented = presentNotification({
            notificationId: `sg-reward-${this.toastCounter++}`, seq: '1', kind: 'PATH_REWARD_READY', severity: 'INFO', params: { expiresAt: SgPathComponent.REWARD.expiresAt }, route: 'PATH',
            createdAt: '2026-10-22T18:41:00Z', expiresAt: '2026-11-21T18:41:00Z'
        });
        if (presented !== null) this.toasts.notify(`sg-reward-toast-${this.toastCounter}`, presented.text, presented.severity, presented.link);
    }

    protected showToast(): void {
        const presented = presentNotification({
            notificationId: `sg-path-${this.toastCounter++}`, seq: '1', kind: 'PATH_MILESTONE', severity: 'INFO', params: { key: 'MEDIA_IN_MATERIAL', done: 6, total: 12 }, route: 'PATH',
            createdAt: '2026-10-22T10:00:00Z', expiresAt: '2026-11-21T10:00:00Z'
        });
        if (presented !== null) this.toasts.notify(`sg-path-toast-${this.toastCounter}`, presented.text, presented.severity, presented.link);
    }

    protected showRhythmToast(): void {
        this.toasts.notify(`sg-rhythm-toast-${this.toastCounter++}`, onboardingText('rhythm.met.toast', { days: daysLabel(5) }), 'INFO', null);
    }

    /** The live hint: stands at the mock button below, exactly as after a chevron (the address of the page is the hint's page). */
    protected showHint(): void {
        this.hint.dismiss();
        const started = this.hint.begin({ title: this.hintCard.title, text: this.hintCard.text, anchor: 'hub.capture' });
        if (started) {
            this.hint.arm();
            this.shown.set(true);
        }
    }

    protected hideHint(): void {
        this.hint.dismiss();
        this.shown.set(false);
    }
}

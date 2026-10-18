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
import { SgSpecimenComponent } from './sg-specimen.component';

/** «Путь Mnema»: the milestone in every state, the chapter and the bar, the in-place hint and the toast of a milestone. */
@Component({
    selector: 'app-sg-path',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, PathMilestoneComponent, PathChapterComponent, PathProgressComponent, PathHintCardComponent, TourAnchorDirective, QuestChooserComponent,
        TourCardComponent, TourPauseComponent],
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

    protected showToast(): void {
        const presented = presentNotification({
            notificationId: `sg-path-${this.toastCounter++}`, seq: '1', kind: 'PATH_MILESTONE', severity: 'INFO', params: { key: 'MEDIA_IN_MATERIAL', done: 6, total: 12 }, route: 'PATH',
            createdAt: '2026-10-22T10:00:00Z', expiresAt: '2026-11-21T10:00:00Z'
        });
        if (presented !== null) this.toasts.notify(`sg-path-toast-${this.toastCounter}`, presented.text, presented.severity, presented.link);
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

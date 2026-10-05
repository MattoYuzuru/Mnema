import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

import { AuthService } from '../../auth.service';
import { GOAL_COPY } from './goal-copy';
import { LEARNING_GOALS, LearningGoal } from './goal.models';
import { LearningGoalStore } from './learning-goal.store';
import { SegmentedChoiceComponent } from '../../shared/segmented-choice.component';

let nextOnboarding = 0;

/**
 * The only places the question appears: the deck list, the profile and the plans page. An allowlist, not a blocklist:
 * every other route (authoring, capture, the workshop, the study session, privacy and terms, the home page, sign-in and
 * any page added later) stays free of it by default. Query and fragment are ignored; a deeper path is not the list.
 */
const ONBOARDING_PATHS: ReadonlySet<string> = new Set(['/decks', '/profile', '/plans']);

export function onboardingSuppressed(url: string): boolean {
    const path = url.split(/[?#]/u)[0].replace(/\/+$/u, '');
    return !ONBOARDING_PATHS.has(path);
}

/**
 * «Для чего вам Mnema?»: asked once after sign-in while the goal is unanswered. It sits above the page, not over it: it does
 * not take focus from the page, and «Пропустить» is as easy as «Продолжить». The answer is stored through the API; the
 * goal only changes some copy and the tier the paywall highlights, and it is never sent to an AI provider.
 */
@Component({
    selector: 'app-goal-onboarding',
    template: `
      @if (visible()) {
        <form class="goal-onboarding paper-surface ruled" (submit)="submit($event)">
          <app-segmented-choice legend="Для чего вам Mnema?" [options]="options" [(value)]="choice" [name]="groupName" />
          <p class="hint">Подстроим подсказки и подскажем подходящий тариф. Ответ можно пропустить; ИИ он не передаётся.</p>
          @if (store.saveFailed()) {
            <p class="notice error" role="alert">Не удалось сохранить ответ. Попробуйте ещё раз или пропустите.</p>
          }
          <div class="actions">
            <button type="submit" class="button primary" [disabled]="choice() === null || store.saving()">Продолжить</button>
            <button type="button" class="button quiet" [disabled]="store.saving()" (click)="skip()">Пропустить</button>
          </div>
        </form>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .goal-onboarding { inline-size: min(100%, 40rem); margin: 1rem auto 0; display: grid; gap: 1rem; }
      .hint { margin: 0; }
      .actions { display: flex; flex-wrap: wrap; gap: .75rem; }
      .actions .button { flex: 1 1 10rem; }
    `],
    imports: [SegmentedChoiceComponent],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class GoalOnboardingComponent {
    protected readonly store = inject(LearningGoalStore);
    private readonly auth = inject(AuthService);
    private readonly router = inject(Router);
    private readonly document = inject(DOCUMENT);

    private readonly uid = `mn-goal-${nextOnboarding++}`;
    protected readonly groupName = `${this.uid}-goal`;
    protected readonly options = LEARNING_GOALS.map(goal => ({ value: goal, label: GOAL_COPY[goal].label }));
    protected readonly choice = signal<LearningGoal | null>(null);

    private readonly status = toSignal(this.auth.status$, { initialValue: this.auth.status() });
    private readonly url = toSignal(this.router.events.pipe(
        filter(event => event instanceof NavigationEnd),
        map(event => event.urlAfterRedirects)
    ), { initialValue: this.router.url });
    /** A skip hides the question at once; the store keeps trying to record it. */
    private readonly dismissed = signal(false);

    protected readonly visible = computed(() => this.status() === 'authenticated' && this.store.state() === 'ready'
        && !this.store.answered() && !this.dismissed() && !onboardingSuppressed(this.url()));

    constructor() {
        effect(() => {
            const status = this.status();
            untracked(() => {
                if (status === 'authenticated') void this.store.load();
                else if (status === 'anonymous') { this.store.reset(); this.dismissed.set(false); this.choice.set(null); }
            });
        });
    }

    protected async submit(event: Event): Promise<void> {
        event.preventDefault();
        const goal = this.choice();
        if (goal === null) return;
        if (await this.store.answer(goal)) this.focusPage();
    }

    protected async skip(): Promise<void> {
        this.dismissed.set(true);
        this.focusPage();
        await this.store.answer(null);
    }

    /** The question disappears from under the focus; hand it to the page heading, or the main region when there is none. */
    private focusPage(): void {
        queueMicrotask(() => (this.document.querySelector<HTMLElement>('#main-content h1')
            ?? this.document.querySelector<HTMLElement>('#main-content'))?.focus());
    }
}

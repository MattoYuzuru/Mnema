import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, Data, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

import { AuthService } from '../../auth.service';
import { GOAL_COPY } from './goal-copy';
import { LEARNING_GOALS, LearningGoal } from './goal.models';
import { LearningGoalStore } from './learning-goal.store';

let nextOnboarding = 0;

/** The route data flag a page sets to keep the question away (the design catalogue sets it; its path is not named here on purpose). */
export const QUIET_ROUTE_DATA = 'quiet';

/**
 * Routes where a question would be in the way: the study session, the sign-in pages and any route that sets
 * {@link QUIET_ROUTE_DATA}. The catalogue is flagged by its route, so a production bundle (which has no catalogue) never
 * mentions it.
 */
export function onboardingSuppressed(url: string, quiet = false): boolean {
    const path = url.split(/[?#]/u)[0];
    return quiet || path.startsWith('/auth/') || path === '/login' || path === '/register'
        || /^\/decks\/[^/]+\/study\/?$/u.test(path);
}

function deepestData(route: ActivatedRouteSnapshot): Data {
    let current = route;
    while (current.firstChild) current = current.firstChild;
    return current.data;
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
          <fieldset [attr.aria-describedby]="hintId">
            <legend [id]="legendId">Для чего вам Mnema?</legend>
            <p class="hint" [id]="hintId">Подстроим подсказки и подскажем подходящий тариф. Ответ можно пропустить; ИИ он не передаётся.</p>
            <div class="choices">
              @for (option of options; track option.goal) {
                <label class="choice">
                  <input type="radio" [name]="legendId" [value]="option.goal" [checked]="option.goal === choice()"
                         (change)="choice.set(option.goal)" />
                  <span>{{ option.label }}</span>
                </label>
              }
            </div>
          </fieldset>
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
      fieldset { margin: 0; border: 0; padding: 0; min-inline-size: 0; display: grid; gap: .75rem; }
      legend { padding: 0; color: var(--mn-ink); font-family: var(--mn-font-display); font-size: clamp(1.4rem, 3vw, 1.8rem); line-height: 1.15; }
      .choices { display: grid; gap: .25rem; }
      .choice { display: flex; align-items: center; gap: .75rem; min-block-size: var(--mn-touch-min); cursor: pointer; }
      .actions { display: flex; flex-wrap: wrap; gap: .75rem; }
      .actions .button { flex: 1 1 10rem; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class GoalOnboardingComponent {
    protected readonly store = inject(LearningGoalStore);
    private readonly auth = inject(AuthService);
    private readonly router = inject(Router);
    private readonly document = inject(DOCUMENT);

    private readonly uid = `mn-goal-${nextOnboarding++}`;
    protected readonly legendId = `${this.uid}-legend`;
    protected readonly hintId = `${this.uid}-hint`;
    protected readonly options = LEARNING_GOALS.map(goal => ({ goal, label: GOAL_COPY[goal].label }));
    protected readonly choice = signal<LearningGoal | null>(null);

    private readonly status = toSignal(this.auth.status$, { initialValue: this.auth.status() });
    private readonly place = toSignal(this.router.events.pipe(
        filter(event => event instanceof NavigationEnd),
        map(event => ({ url: event.urlAfterRedirects, quiet: deepestData(this.router.routerState.snapshot.root)[QUIET_ROUTE_DATA] === true }))
    ), { initialValue: { url: this.router.url, quiet: deepestData(this.router.routerState.snapshot.root)[QUIET_ROUTE_DATA] === true } });
    /** A skip hides the question at once; the store keeps trying to record it. */
    private readonly dismissed = signal(false);

    protected readonly visible = computed(() => this.status() === 'authenticated' && this.store.state() === 'ready'
        && !this.store.answered() && !this.dismissed() && !onboardingSuppressed(this.place().url, this.place().quiet));

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

    /** The question disappears from under the focus; hand it to the page heading as a route change does. */
    private focusPage(): void {
        queueMicrotask(() => this.document.querySelector<HTMLElement>('#main-content h1')?.focus());
    }
}

import { ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, inject, signal } from '@angular/core';

import { TourAnchorDirective } from '../../core/onboarding/tour-anchor.directive';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { SegmentedChoiceComponent } from '../../shared/segmented-choice.component';
import { GOAL_COPY } from './goal-copy';
import { LEARNING_GOALS, LearningGoal } from './goal.models';
import { LearningGoalStore } from './learning-goal.store';

let nextBlock = 0;

/**
 * The «Цель» block of the profile: the answer to «Для чего вам Mnema?» where the learner can read it and change it (the survey promises
 * «изменить их можно в профиле»). Unanswered, it asks the question with a skip; answered, it says the goal and offers «Изменить»; a skip
 * says there is no goal and offers «Выбрать». It reads and writes through `LearningGoalStore` (`PUT /api/learning-profile`), so the shell's
 * question and the plans page follow it. It carries the `profile.goal` anchor of the milestone «Расскажите о цели» whatever the survey does:
 * the block is the place, not the question of the shell.
 */
@Component({
    selector: 'app-profile-goal',
    imports: [SegmentedChoiceComponent, TourAnchorDirective],
    template: `
      <section class="goal-block" aria-labelledby="goal-block-title" mnTourAnchor="profile.goal">
        <h2 id="goal-block-title">{{ text.title }}</h2>
        @switch (store.state()) {
          @case ('ready') {
            @if (store.answered() && !editing()) {
              <p>{{ store.goal() === null ? text.none : current() }}</p>
              <div class="actions"><button type="button" class="button" (click)="edit()">{{ store.goal() === null ? text.choose : text.change }}</button></div>
            } @else {
              <form (submit)="submit($event)">
                <app-segmented-choice [legend]="text.question" [options]="options" [(value)]="choice" [name]="groupName" />
                <p class="hint">{{ text.hint }}</p>
                @if (store.saveFailed()) { <p class="notice error" role="alert">{{ text.saveError }}</p> }
                <div class="actions">
                  <button type="submit" class="button primary" [disabled]="choice() === null || store.saving()">{{ text.save }}</button>
                  @if (!store.answered()) {
                    <button type="button" class="button quiet" [disabled]="store.saving()" (click)="skip()">{{ text.skip }}</button>
                  } @else {
                    <button type="button" class="button quiet" [disabled]="store.saving()" (click)="cancel()">{{ text.cancel }}</button>
                  }
                </div>
              </form>
            }
          }
          @case ('error') {
            <p role="alert">{{ text.error }}</p>
            <div class="actions"><button type="button" class="button" (click)="load()">{{ text.retry }}</button></div>
          }
          @default { <p role="status">{{ text.loading }}</p> }
        }
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .goal-block { display: grid; gap: .9rem; justify-items: start; min-inline-size: 0; border-block-start: 1px solid var(--mn-ink); border-block-end: 1px solid var(--mn-rule); padding: clamp(1.25rem, 3vw, 2rem); background: var(--mn-sheet); }
      h2 { margin: 0; color: var(--mn-ink); font: 500 clamp(1.8rem, 3vw, 2.25rem)/1.05 var(--mn-font-display); }
      p { margin: 0; }
      form { display: grid; gap: .9rem; justify-items: start; inline-size: min(100%, 40rem); }
      .actions { display: flex; flex-wrap: wrap; gap: .75rem; }
      @media (max-width: 36rem) { .goal-block { padding-inline: 0; background: transparent; } }
      @media (forced-colors: active) { .goal-block { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfileGoalComponent {
    protected readonly store = inject(LearningGoalStore);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    protected readonly options = LEARNING_GOALS.map(goal => ({ value: goal, label: GOAL_COPY[goal].label }));
    protected readonly choice = signal<LearningGoal | null>(null);
    protected readonly editing = signal(false);
    protected readonly groupName = `mn-profile-goal-${nextBlock++}`;
    protected readonly text = {
        title: onboardingText('goal.profile.title'), question: onboardingText('survey.goal.title'), hint: onboardingText('survey.goal.hint'),
        loading: onboardingText('goal.profile.loading'), error: onboardingText('goal.profile.error'), saveError: onboardingText('goal.profile.saveError'),
        none: onboardingText('goal.profile.none'), change: onboardingText('goal.profile.change'), choose: onboardingText('goal.profile.choose'),
        save: onboardingText('goal.profile.save'), cancel: onboardingText('goal.profile.cancel'), skip: onboardingText('survey.skip'), retry: onboardingText('path.retry')
    };

    constructor() {
        void this.store.load();
    }

    protected current(): string {
        const goal = this.store.goal();
        return goal === null ? '' : onboardingText('goal.profile.current', { goal: GOAL_COPY[goal].label });
    }

    protected load(): void { void this.store.load(); }

    protected edit(): void {
        this.choice.set(this.store.goal());
        this.editing.set(true);
        // The button that was pressed is gone: the focus goes to the choice (the checked option, else the first one), not to the body.
        this.focusAfterRender('form input:checked, form input');
    }

    protected cancel(): void {
        this.editing.set(false);
        this.focusAfterRender('.actions button');
    }

    protected async submit(event: Event): Promise<void> {
        event.preventDefault();
        const goal = this.choice();
        if (goal === null) return;
        if (await this.store.answer(goal)) {
            this.editing.set(false);
            this.focusAfterRender('.actions button');
        }
    }

    protected async skip(): Promise<void> {
        if (await this.store.answer(null)) this.focusAfterRender('.actions button');
    }

    /** After the next render, the first element matching `selector` in the block takes the focus (the control the learner's action replaced). */
    private focusAfterRender(selector: string): void {
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), { injector: this.injector });
    }
}

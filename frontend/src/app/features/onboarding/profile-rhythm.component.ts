import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { JourneyStore } from '../../core/onboarding/journey.store';
import { EXPERIENCES, Experience, PreferenceChanges, WEEKLY_TARGET, isApplied, isRefused } from '../../core/onboarding/onboarding.models';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { daysLabel } from '../../core/onboarding/rhythm-format';
import { TourAnchorDirective } from '../../core/onboarding/tour-anchor.directive';
import { RhythmWeekComponent } from '../../shared/rhythm-week.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';

type Problem = 'error' | 'conflict';
type ExperienceKey = 'new' | 'familiar' | 'confident';

const TARGETS: readonly number[] = Array.from({ length: WEEKLY_TARGET.max - WEEKLY_TARGET.min + 1 }, (_, index) => WEEKLY_TARGET.min + index);

/**
 * The rhythm block of the profile (OB-401), the place `PROFILE_RHYTHM` of «Путь» points at: the seven days of this week, the target, the
 * weeks in rhythm (**only here**, never on the summary of a session), and the two answers of the survey the learner can change: days a week
 * (1–7) and the experience. «Изменить их можно в профиле» is promised by the survey; this is the place. One form, saved with «Сохранить» through
 * the preferences command of the journey (`PUT /api/onboarding/preferences`, which works from the first day, with or without a tour). The
 * choices are the learner's draft and are never overwritten by a re-read: a stale version (412) leaves them as they are with a notice, so
 * saving again is one press. The button is `aria-disabled`, never `disabled`, so the focus stays where it is; the result is said in a polite
 * status. Shown while onboarding is on and the view carries a rhythm.
 */
@Component({
    selector: 'app-profile-rhythm',
    imports: [RhythmWeekComponent, SegmentedChoiceComponent, TourAnchorDirective],
    template: `
      @if (rhythm(); as week) {
        <section class="rhythm-block" id="profile-rhythm" aria-labelledby="profile-rhythm-title" mnTourAnchor="profile.rhythm">
          <h2 id="profile-rhythm-title">{{ text.title }}</h2>
          <app-rhythm-week [weekStart]="week.weekStart" [today]="week.today" [days]="week.days" />
          <p class="target">{{ target() }}</p>
          @if (week.weeksMet > 0) { <p class="weeks">{{ weeks() }}</p> }
          <form (submit)="submit($event)">
            <app-segmented-choice [legend]="text.daysLegend" [options]="dayOptions" [value]="daysChoice()" (valueChange)="chooseDays($event)"
              name="profile-rhythm-days" [busy]="saving()" />
            <app-segmented-choice [legend]="text.experienceLegend" [options]="experienceOptions" [value]="experienceChoice()" (valueChange)="chooseExperience($event)"
              name="profile-rhythm-experience" [busy]="saving()" />
            <p class="hint">{{ text.hint }}</p>
            @if (problem(); as kind) {
              <p class="notice" [class.error]="kind === 'error'" [class.warning]="kind === 'conflict'" role="alert">{{ kind === 'error' ? text.error : text.conflict }}</p>
            }
            <div class="actions">
              <button type="submit" class="button primary" [attr.aria-disabled]="dirty() && !saving() ? null : 'true'">{{ text.save }}</button>
            </div>
            <p class="hint status" role="status">{{ status() }}</p>
          </form>
        </section>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .rhythm-block { display: grid; gap: .9rem; justify-items: start; min-inline-size: 0; border-block-start: 1px solid var(--mn-ink); border-block-end: 1px solid var(--mn-rule); padding: clamp(1.25rem, 3vw, 2rem); background: var(--mn-sheet); }
      h2 { margin: 0; color: var(--mn-ink); font: 500 clamp(1.8rem, 3vw, 2.25rem)/1.05 var(--mn-font-display); overflow-wrap: anywhere; }
      p { margin: 0; }
      .target { color: var(--mn-ink); font-weight: 650; }
      form { display: grid; gap: .9rem; justify-items: start; min-inline-size: 0; max-inline-size: 100%; }
      .status:empty { display: none; }
      @media (max-width: 36rem) { .rhythm-block { padding-inline: 0; background: transparent; } }
      @media (forced-colors: active) { .rhythm-block { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfileRhythmComponent {
    private readonly store = inject(JourneyStore);

    protected readonly dayOptions: readonly SegmentedOption<string>[] = TARGETS.map(count => ({
        value: String(count), label: count === WEEKLY_TARGET.max ? onboardingText('survey.days.everyday') : daysLabel(count)
    }));
    protected readonly experienceOptions: readonly SegmentedOption<Experience>[] = EXPERIENCES.map(experience => {
        const key = experience.toLowerCase() as ExperienceKey;
        return { value: experience, label: onboardingText(`survey.experience.${key}`), hint: onboardingText(`survey.experience.${key}.hint`) };
    });
    protected readonly text = {
        title: onboardingText('rhythm.settings.title'), daysLegend: onboardingText('survey.days.title'), experienceLegend: onboardingText('survey.experience.title'),
        hint: onboardingText('rhythm.settings.hint'), save: onboardingText('rhythm.settings.save'),
        error: onboardingText('rhythm.settings.error'), conflict: onboardingText('rhythm.settings.conflict')
    };

    /** The rhythm of the view while onboarding is on; `null` hides the block. */
    protected readonly rhythm = computed(() => {
        const view = this.store.view();
        return view?.enabled === true ? view.rhythm : null;
    });
    protected readonly target = computed(() => onboardingText('rhythm.target', { days: daysLabel(this.rhythm()?.target ?? WEEKLY_TARGET.min) }));
    protected readonly weeks = computed(() => onboardingText('rhythm.profile.weeks', { n: this.rhythm()?.weeksMet ?? 0 }));

    /** The learner's unsaved choices; `undefined`: untouched, the saved value shows. */
    private readonly daysDraft = signal<number | undefined>(undefined);
    private readonly experienceDraft = signal<Experience | undefined>(undefined);
    protected readonly saving = signal(false);
    protected readonly problem = signal<Problem | null>(null);
    protected readonly status = signal('');

    protected readonly daysChoice = computed(() => String(this.daysDraft() ?? this.rhythm()?.target ?? ''));
    protected readonly experienceChoice = computed(() => this.experienceDraft() ?? this.store.journey()?.experience ?? null);
    /** What would be sent: only the answers that differ from the saved ones. */
    private readonly changes = computed<PreferenceChanges>(() => {
        const days = this.daysDraft();
        const experience = this.experienceDraft();
        return {
            ...(days !== undefined && days !== this.rhythm()?.target ? { weeklyTarget: days } : {}),
            ...(experience !== undefined && experience !== this.store.journey()?.experience ? { experience } : {})
        };
    });
    protected readonly dirty = computed(() => Object.keys(this.changes()).length > 0);

    protected chooseDays(value: string | null): void {
        if (value === null) return;
        this.daysDraft.set(Number(value));
        this.touched();
    }

    protected chooseExperience(value: Experience | null): void {
        if (value === null) return;
        this.experienceDraft.set(value);
        this.touched();
    }

    protected async submit(event: Event): Promise<void> {
        event.preventDefault();
        const changes = this.changes();
        if (this.saving() || Object.keys(changes).length === 0) return;
        this.saving.set(true);
        this.problem.set(null);
        this.status.set('');
        const result = await this.store.setPreferences(changes);
        this.saving.set(false);
        if (isApplied(result)) {
            this.daysDraft.set(undefined);
            this.experienceDraft.set(undefined);
            this.status.set(onboardingText('rhythm.settings.saved'));
        } else {
            // A stale version was re-read and the choices stay as they are; saving again sends them with the current version.
            this.problem.set(isRefused(result) ? 'conflict' : 'error');
        }
    }

    private touched(): void {
        this.status.set('');
        this.problem.set(null);
    }
}

import { DOCUMENT } from '@angular/common';
import { Title } from '@angular/platform-browser';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Journey } from '../../core/onboarding/onboarding.models';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { PathNavigation } from '../../core/onboarding/path-navigation.service';
import { PATH_STATIC_HREFS } from '../../core/onboarding/path-places';
import { PathPreferences } from '../../core/onboarding/path-preferences';
import { MilestoneChapter, MilestoneKey, milestoneDefinition, pathChapters } from '../../core/onboarding/path.models';
import { PathChapterComponent } from '../../shared/path-chapter.component';
import { PathMilestoneComponent } from '../../shared/path-milestone.component';
import { PathProgressComponent } from '../../shared/path-progress.component';
import { RewardCardComponent } from '../../shared/reward-card.component';
import { ReplayTourComponent } from './replay-tour.component';

/**
 * «Путь Mnema» (`/profile/path`): what Mnema can do, as a finite list of milestones in four chapters, each marked when the learner does
 * it for real. The current chapter (the first with something left to do) is open, the others are folded with their count; milestones that
 * a switched-off feature hides are not drawn. A chevron takes the learner to the place with an in-place hint (`PathNavigation`).
 * The page exists only while the server says onboarding is on (the rollback switch): otherwise it sends the learner back to the profile.
 * While the finished path's reward is active the page shows it above the chapters (the gift, its code and «Выбрать тариф»), once it was redeemed one quiet line, and
 * nothing once it has expired. Reading the journey never blocks anything: a failure is a message with «Повторить», not a broken page.
 */
@Component({
    selector: 'app-path-page',
    imports: [RouterLink, PathChapterComponent, PathMilestoneComponent, PathProgressComponent, ReplayTourComponent, RewardCardComponent],
    template: `
      @if (!off()) {
      <section class="path-page" aria-labelledby="path-title">
        @if (showNotice()) {
          <p class="notice path-new"><span class="stamp">{{ text.stamp }}</span><span>{{ text.notice }}</span></p>
        }
        <header class="path-head">
          <div class="heading">
            <a class="eyebrow back" routerLink="/profile">{{ text.eyebrow }}</a>
            <h1 id="path-title" tabindex="-1">{{ text.title }}</h1>
            <p class="hint intro">{{ text.intro }}</p>
          </div>
          @if (path(); as summary) {
            <div class="total">
              <strong aria-hidden="true">{{ totalText() }}</strong>
              <app-path-progress [label]="text.title" [value]="summary.done" [max]="summary.total" [valueText]="totalText()" />
            </div>
          }
        </header>

        @if (store.view() === null) {
          @if (store.error() === 'load') {
            <div class="notice error" role="alert">
              <p>{{ text.error }}</p>
              <button type="button" class="button" (click)="retry()">{{ text.retry }}</button>
            </div>
          } @else {
            <p class="status" role="status">{{ text.loading }}</p>
          }
        } @else if (store.enabled()) {
          @if (reward(); as given) { <app-reward-card [reward]="given" [level]="2" /> }
          @for (chapter of chapters(); track chapter.chapter) {
            <app-path-chapter [name]="chapterName(chapter.chapter)" [done]="chapter.done" [total]="chapter.milestones.length" [open]="chapter.current">
              <ul class="milestones">
                @for (milestone of chapter.milestones; track milestone.key) {
                  <li>
                    <app-path-milestone [key]="milestone.key" [state]="milestone.state" [progress]="milestone.progress" [target]="milestone.target"
                      [href]="hrefOf(milestone.key)" (follow)="navigation.go(milestone.key)" />
                  </li>
                }
              </ul>
            </app-path-chapter>
          }
          <div class="actions">
            <app-replay-tour />
            <button type="button" class="button quiet" (click)="toggleHidden()">{{ preferences.hidden() ? text.show : text.hide }}</button>
          </div>
        }
      </section>
      }
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; color: var(--mn-body); }
      * { box-sizing: border-box; }
      .path-page { display: grid; gap: 1.25rem; inline-size: min(100%, var(--mn-workspace-width)); margin-inline: auto; padding: clamp(1.5rem, 3vw, 2.5rem) clamp(1.125rem, 5vw, 3rem); }
      .path-new { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; }
      .path-head { display: flex; flex-wrap: wrap; gap: 1rem; align-items: end; justify-content: space-between; }
      .heading { min-inline-size: 0; max-inline-size: 45rem; }
      /* Layout deltas only: the look of the rubric and of the hint is the shared .eyebrow and .hint. */
      .back { display: inline-flex; align-items: center; min-block-size: var(--mn-touch-min); text-decoration: none; }
      .back:hover { text-decoration: underline; text-underline-offset: .22em; }
      h1 { margin: 0 0 .5rem; color: var(--mn-ink); font: 500 clamp(2.7rem, 7vw, 5rem)/.98 var(--mn-font-display); overflow-wrap: anywhere; }
      h1:focus-visible { outline: none; }
      .intro { margin: 0; }
      .total { display: grid; gap: .35rem; min-inline-size: min(100%, 16rem); }
      .total strong { color: var(--mn-ink); font-size: 1.3rem; }
      .status, .notice p { margin: 0; }
      .notice.error { display: grid; gap: .75rem; justify-items: start; }
      .milestones { list-style: none; margin: 0; padding: 0; }
      .actions { display: flex; flex-wrap: wrap; gap: .75rem; align-items: center; }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PathPageComponent implements OnInit {
    protected readonly store = inject(JourneyStore);
    protected readonly navigation = inject(PathNavigation);
    protected readonly preferences = inject(PathPreferences);
    private readonly router = inject(Router);
    private readonly title = inject(Title);
    private readonly document = inject(DOCUMENT);

    protected readonly text = {
        eyebrow: onboardingText('path.eyebrow'), title: onboardingText('path.title'), intro: onboardingText('path.intro'),
        stamp: onboardingText('path.new.stamp'), notice: onboardingText('path.new'), error: onboardingText('path.error'),
        retry: onboardingText('path.retry'), loading: onboardingText('path.loading'), hide: onboardingText('path.hide'), show: onboardingText('path.show')
    };
    /** The server says onboarding is off: nothing of «Путь» is drawn while the learner is sent back to the profile. */
    protected readonly off = computed(() => this.store.view() !== null && !this.store.enabled());
    protected readonly path = computed(() => this.store.view()?.path ?? null);
    /** The reward of the finished path while there is something to say about it (an active one, or one that was redeemed); an expired one is not mentioned. */
    protected readonly reward = computed(() => {
        const reward = this.store.view()?.reward ?? null;
        return reward !== null && reward.state !== 'EXPIRED' ? reward : null;
    });
    protected readonly chapters = computed(() => pathChapters(this.store.view()?.milestones ?? []));
    protected readonly totalText = computed(() => {
        const summary = this.path();
        return summary === null ? '' : onboardingText('path.total', { done: summary.done, total: summary.total });
    });
    /** «Новое: Путь Mnema», once per account on this device, for an account the tour was never offered to by itself (see `neverOffered`). Kept on screen for this visit. */
    protected readonly showNotice = signal(false);

    constructor() {
        const previous = this.document.title;
        this.title.setTitle(this.text.title);
        inject(DestroyRef).onDestroy(() => this.title.setTitle(previous));
        // The page exists only while onboarding is on: once the server has said it is off, the learner goes back to the profile.
        effect(() => {
            const answered = this.store.view() !== null;
            const enabled = this.store.enabled();
            if (answered && !enabled) untracked(() => void this.router.navigateByUrl('/profile', { replaceUrl: true }));
        });
        effect(() => {
            const journey = this.store.journey();
            if (!this.store.enabled() || journey === null) return;
            untracked(() => {
                if (!neverOffered(journey) || this.preferences.noticeSeen()) return;
                this.showNotice.set(true);
                this.preferences.markNoticeSeen();
            });
        });
    }

    ngOnInit(): void {
        // The view is read once per entry (and after every milestone notification); the page asks again with the held validator, so a quiet account costs a 304.
        void this.store.load({ refresh: true });
    }

    protected retry(): void {
        void this.store.load({ refresh: true });
    }

    protected toggleHidden(): void {
        this.preferences.setHidden(!this.preferences.hidden());
    }

    protected chapterName(chapter: MilestoneChapter): string {
        return onboardingText(`path.chapter.${chapter}`);
    }

    protected hrefOf(key: MilestoneKey): string | null {
        return PATH_STATIC_HREFS[milestoneDefinition(key).route];
    }
}


/**
 * Whether the tour was never offered to this account on its own: an old account or one outside the eligibility (`eligible=false`), or the
 * holdout (eligible, still `OFFERED`, and `autoOffer=false`, which the server says only for the group that is not offered it). The view
 * does not name the holdout once it has left `OFFERED` (a replay or a start), so a holdout that went through the tour is treated as
 * offered from then on: it has been introduced to «Путь» by the tour's own finale.
 */
export function neverOffered(journey: Journey): boolean {
    return !journey.eligible || (journey.state === 'OFFERED' && !journey.autoOffer);
}

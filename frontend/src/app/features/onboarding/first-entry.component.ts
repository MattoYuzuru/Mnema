import { DOCUMENT, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterRenderEffect, computed, effect, inject, linkedSignal, signal, untracked, viewChild } from '@angular/core';

import { JourneyStore } from '../../core/onboarding/journey.store';
import { Branch, Experience } from '../../core/onboarding/onboarding.models';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { daysLabel } from '../../core/onboarding/rhythm-format';
import { NightSkyComponent } from '../../shared/night-sky.component';
import { InterestPickerComponent } from '../../shared/interest-picker.component';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { SurveyCardComponent } from '../../shared/survey-card.component';
import { InterestStore } from '../community/interest-store.service';
import { GOAL_COPY } from '../goal/goal-copy';
import { LEARNING_GOALS, LearningGoal } from '../goal/goal.models';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { FirstEntryCard, OnboardingFlow } from './onboarding-flow.service';

const EXPERIENCES: readonly Experience[] = ['NEW', 'FAMILIAR', 'CONFIDENT'];
const DAYS: readonly number[] = [2, 3, 5, 7];
const SAME_CARD_BRANCH_LABEL: Readonly<Record<Branch, 'branch.sample' | 'branch.community' | 'branch.own'>> = {
    SAMPLE: 'branch.sample', COMMUNITY: 'branch.community', OWN: 'branch.own'
};

export { daysLabel };

/**
 * «Начало пути»: the first-entry surface, a full-screen modal `<dialog>` on the night: the welcome (with the stars; the survey and the
 * branch choice are the same night without them), up to four survey cards, and the branch choice. The cards themselves are decided by
 * `OnboardingFlow`; this component draws the card it is given, takes the focus to its heading and carries the learner's answers to the
 * stores that already own them (the goal, the interests) and to the journey (experience, days).
 */
@Component({
    selector: 'app-first-entry',
    imports: [NightSkyComponent, SurveyCardComponent, SegmentedChoiceComponent, InterestPickerComponent, NgTemplateOutlet],
    template: `
      @if (flow.card(); as card) {
        <dialog #surface class="first-entry" closedby="none" [attr.aria-label]="dialogLabel()" (cancel)="escape($event)" (keydown.escape)="escape($event)" (close)="reopen()">
          <app-night-sky layout="panel" seed="mnema-first-entry" [stars]="card === 'welcome'">
            @if (card === 'welcome') {
              <p class="eyebrow">{{ text('welcome.eyebrow') }}</p>
              <h1 #welcomeHeading id="first-entry-welcome" tabindex="-1">{{ text('welcome.title') }}</h1>
              <p class="lead">{{ text('welcome.lead') }}</p>
              <div class="actions">
                <button type="button" class="button primary" [attr.aria-disabled]="flow.busy() ? 'true' : null" (click)="flow.start()">{{ text('welcome.start') }}</button>
                <button type="button" class="button quiet" [attr.aria-disabled]="flow.busy() ? 'true' : null" (click)="flow.skipAll()">{{ text('welcome.skip') }}</button>
              </div>
            } @else if (card === 'survey.goal') {
              <app-survey-card [title]="text('survey.goal.title')" [question]="flow.questionNumber(card)">
                <p class="hint">{{ text('survey.goal.hint') }}</p>
                <app-segmented-choice [legend]="text('survey.goal.title')" [legendHidden]="true" [options]="goalOptions" [(value)]="goal" name="first-entry-goal" />
                <ng-container ngProjectAs="[actions]"><ng-container *ngTemplateOutlet="navigation; context: { ready: goal() !== null, submit: answerGoal }" /></ng-container>
                <ng-container ngProjectAs="[note]"><ng-container *ngTemplateOutlet="privacy" /></ng-container>
              </app-survey-card>
            } @else if (card === 'survey.interests') {
              <app-survey-card [title]="text('survey.interests.title')" [question]="flow.questionNumber(card)">
                <p class="hint">{{ text('survey.interests.hint') }}</p>
                @if (interests.profile(); as profile) {
                  <app-interest-picker [topics]="profile.availableTopics" [selected]="topics()" [busy]="interests.busy()" [legendHidden]="true"
                    (changed)="topics.set($event)" (save)="saveInterests($event)" />
                }
                @if (interests.error()) { <p class="field-error" role="alert">{{ interests.error() }}</p> }
                <ng-container ngProjectAs="[actions]"><ng-container *ngTemplateOutlet="navigation; context: { ready: null, submit: skipInterests }" /></ng-container>
                <ng-container ngProjectAs="[note]"><ng-container *ngTemplateOutlet="privacy" /></ng-container>
              </app-survey-card>
            } @else if (card === 'survey.experience') {
              <app-survey-card [title]="text('survey.experience.title')" [question]="flow.questionNumber(card)">
                <app-segmented-choice [legend]="text('survey.experience.title')" [legendHidden]="true" [options]="experienceOptions" [(value)]="experience" name="first-entry-experience" />
                <ng-container ngProjectAs="[actions]"><ng-container *ngTemplateOutlet="navigation; context: { ready: experience() !== null, submit: answerExperience }" /></ng-container>
                <ng-container ngProjectAs="[note]"><ng-container *ngTemplateOutlet="privacy" /></ng-container>
              </app-survey-card>
            } @else if (card === 'survey.days') {
              <app-survey-card [title]="text('survey.days.title')" [question]="flow.questionNumber(card)">
                <p class="hint">{{ text('survey.days.hint') }}</p>
                <app-segmented-choice [legend]="text('survey.days.title')" [legendHidden]="true" [options]="dayOptions" [(value)]="days" name="first-entry-days" />
                <ng-container ngProjectAs="[actions]"><ng-container *ngTemplateOutlet="navigation; context: { ready: days() !== null, submit: answerDays }" /></ng-container>
                <ng-container ngProjectAs="[note]"><ng-container *ngTemplateOutlet="privacy" /></ng-container>
              </app-survey-card>
            } @else if (card === 'choose') {
              <app-survey-card [title]="text('branch.title')">
                <fieldset class="choices">
                  <legend class="visually-hidden">{{ text('branch.title') }}</legend>
                  @for (branch of flow.branches(); track branch) {
                    <label class="choice">
                      <input type="radio" name="first-entry-branch" [value]="branch" [checked]="selectedBranch() === branch" (change)="pickedBranch.set(branch)" />
                      <span>
                        <strong>{{ branchLabel(branch) }}@if (branch === 'SAMPLE' && newcomer()) { <span class="stamp">{{ text('branch.sample.stamp') }}</span> }</strong>
                        <span class="hint">{{ branchHint(branch) }}</span>
                      </span>
                    </label>
                  }
                </fieldset>
                <!-- Present before its text changes, so the wait for the sample copy is heard. -->
                <p class="hint" role="status">{{ flow.preparingSample() ? text('tour.preparing') : '' }}</p>
                <ng-container ngProjectAs="[actions]"><ng-container *ngTemplateOutlet="branchActions" /></ng-container>
              </app-survey-card>
            } @else {
              <app-survey-card [title]="text('replay.own.title')">
                <fieldset class="choices">
                  <legend class="visually-hidden">{{ text('replay.own.title') }}</legend>
                  <label class="choice">
                    <input type="radio" name="first-entry-deck" [checked]="pickedDeck() === null" (change)="pickedDeck.set(null)" />
                    <span><strong>{{ text('replay.own.new') }}</strong></span>
                  </label>
                  @for (deck of flow.replayDecks(); track deck.deckId) {
                    <label class="choice">
                      <input type="radio" name="first-entry-deck" [checked]="pickedDeck() === deck.deckId" (change)="pickedDeck.set(deck.deckId)" />
                      <span><strong>{{ deck.title }}</strong></span>
                    </label>
                  }
                </fieldset>
                <div actions class="forward-only">
                  <button type="button" class="button quiet" (click)="flow.back()">{{ text('survey.back') }}</button>
                  <button type="button" class="button primary" [attr.aria-disabled]="flow.busy() ? 'true' : null" (click)="flow.chooseReplayDeck(pickedDeck())">{{ text('branch.continue') }}</button>
                </div>
              </app-survey-card>
            }
          </app-night-sky>
        </dialog>
      }

      <ng-template #navigation let-ready="ready" let-submit="submit">
        <div class="steps">
          @if (flow.canGoBack()) { <button type="button" class="button quiet" (click)="flow.back()">{{ text('survey.back') }}</button> } @else { <span></span> }
        </div>
        <div class="forward">
          <button type="button" class="button" (click)="flow.skip()">{{ text('survey.skip') }}</button>
          @if (ready !== null) {
            <button type="button" class="button primary" [attr.aria-disabled]="ready === false || flow.busy() ? 'true' : null" (click)="ready === true && submit()">{{ nextLabel() }}</button>
          }
        </div>
      </ng-template>
      <ng-template #privacy><p class="hint privacy-note">{{ text('survey.privacy') }}</p></ng-template>
      <ng-template #branchActions>
        <div class="steps">
          @if (flow.canGoBack()) { <button type="button" class="button quiet" (click)="flow.back()">{{ text('survey.back') }}</button> } @else { <span></span> }
        </div>
        <button type="button" class="button primary" [attr.aria-disabled]="flow.busy() || selectedBranch() === null ? 'true' : null" (click)="startBranch()">{{ text('branch.continue') }}</button>
      </ng-template>
    `,
    styleUrl: './first-entry.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class FirstEntryComponent {
    protected readonly flow = inject(OnboardingFlow);
    protected readonly interests = inject(InterestStore);
    private readonly goalStore = inject(LearningGoalStore);
    private readonly journey = inject(JourneyStore);
    private readonly document = inject(DOCUMENT);
    private readonly surface = viewChild<ElementRef<HTMLDialogElement>>('surface');

    protected readonly goalOptions: readonly SegmentedOption<LearningGoal>[] = LEARNING_GOALS.map(goal => ({ value: goal, label: GOAL_COPY[goal].label }));
    protected readonly experienceOptions: readonly SegmentedOption<Experience>[] = EXPERIENCES.map(experience => ({
        value: experience,
        label: onboardingText(`survey.experience.${experience.toLowerCase() as 'new' | 'familiar' | 'confident'}`),
        hint: onboardingText(`survey.experience.${experience.toLowerCase() as 'new' | 'familiar' | 'confident'}.hint`)
    }));
    /** The days hint is said once, under the group (the prototype's `.hint`), not repeated on every option. */
    protected readonly dayOptions: readonly SegmentedOption<string>[] = DAYS.map(count => ({
        value: String(count), label: count === 7 ? onboardingText('survey.days.everyday') : daysLabel(count)
    }));

    protected readonly goal = signal<LearningGoal | null>(null);
    protected readonly experience = signal<Experience | null>(null);
    protected readonly days = signal<string | null>(null);
    /** The topics chosen in the picker; they start from what is already saved. */
    protected readonly topics = linkedSignal<readonly string[]>(() => this.interests.profile()?.selectedTopics.map(topic => topic.id) ?? []);
    protected readonly pickedBranch = signal<Branch | null>(null);
    protected readonly pickedDeck = signal<string | null>(null);
    /** The branch the radios show: the learner's pick, else the first option (the sample for a newcomer, where it exists). */
    protected readonly selectedBranch = computed<Branch | null>(() => {
        const options = this.flow.branches();
        const picked = this.pickedBranch();
        return picked !== null && options.includes(picked) ? picked : options[0] ?? null;
    });
    protected readonly newcomer = computed(() => this.journey.journey()?.experience === 'NEW');
    /** The dialog is named by the heading of its card. */
    protected readonly dialogLabel = computed(() => {
        switch (this.flow.card()) {
            case 'welcome': return onboardingText('welcome.title');
            case 'survey.goal': return onboardingText('survey.goal.title');
            case 'survey.interests': return onboardingText('survey.interests.title');
            case 'survey.experience': return onboardingText('survey.experience.title');
            case 'survey.days': return onboardingText('survey.days.title');
            case 'replay.deck': return onboardingText('replay.own.title');
            default: return onboardingText('branch.title');
        }
    });
    /** «Готово» on the last question, «Дальше» before it. */
    protected readonly nextLabel = computed(() => {
        const card = this.flow.card();
        return card === 'survey.days' ? onboardingText('survey.done') : onboardingText('survey.next');
    });
    private readonly survey = viewChild(SurveyCardComponent);
    /** The card whose interests save is on its way; the survey moves on from that card only. */
    private waitingFrom: FirstEntryCard | null = null;

    constructor() {
        // The dialog is open exactly while there is a card; a modal dialog makes the page behind inert and keeps Tab inside.
        afterRenderEffect(() => {
            const dialog = this.surface()?.nativeElement;
            if (dialog !== undefined && this.flow.card() !== null) untracked(() => { if (!dialog.open) dialog.showModal(); });
        });
        // The heading of the card takes the focus whenever the card changes: the welcome's own h1, or the heading of the survey card.
        afterRenderEffect(() => {
            if (this.flow.card() === null) return;
            const welcome = this.document.getElementById('first-entry-welcome');
            const card = this.survey();
            untracked(() => { if (welcome !== null) welcome.focus({ preventScroll: true }); else card?.focusHeading(); });
        });
        // The saved interests continue the survey once the save is answered without an error.
        effect(() => {
            const busy = this.interests.busy();
            untracked(() => {
                const from = this.waitingFrom;
                if (from === null || busy) return;
                this.waitingFrom = null;
                if (this.interests.error() === '') this.flow.advance(from);
            });
        });
        inject(DestroyRef).onDestroy(() => { if (this.surface()?.nativeElement.open) this.surface()?.nativeElement.close(); });
    }

    protected text(key: Parameters<typeof onboardingText>[0]): string {
        return onboardingText(key);
    }

    protected branchLabel(branch: Branch): string { return onboardingText(SAME_CARD_BRANCH_LABEL[branch]); }
    protected branchHint(branch: Branch): string { return onboardingText(`${SAME_CARD_BRANCH_LABEL[branch]}.hint`); }

    protected readonly answerGoal = (): void => { void this.flow.answerGoal(this.goal()); };
    protected readonly answerExperience = (): void => { const value = this.experience(); if (value !== null) void this.flow.answerExperience(value); };
    protected readonly answerDays = (): void => { const value = this.days(); if (value !== null) void this.flow.answerDays(Number(value)); };
    protected readonly skipInterests = (): void => this.flow.skipInterests();

    protected saveInterests(ids: readonly string[]): void {
        this.waitingFrom = this.flow.card();
        this.interests.save(ids);
    }

    /**
     * Escape: the surface is never dismissed by the browser, but once the journey has begun it is the learner's way out, the same
     * pause as the tour's (the welcome only offers, so there is nothing to pause there). `closedby="none"` may keep the browser from
     * sending `cancel` at all, so the key itself is heard too; the pause is guarded against the two arriving together.
     */
    protected escape(event: Event): void {
        if (event.defaultPrevented && event.type === 'keydown') return;
        event.preventDefault();
        void this.flow.pause();
    }

    /**
     * The surface is not dismissed by the browser: `closedby="none"` is the declarative form, and this is the fallback for browsers
     * that still honour a second Escape through the close watcher (the first one is cancelled, the second closes without asking).
     */
    protected reopen(): void {
        const dialog = this.surface()?.nativeElement;
        if (dialog !== undefined && this.flow.card() !== null && !dialog.open) dialog.showModal();
    }

    protected startBranch(): void {
        const branch = this.selectedBranch();
        if (branch !== null) void this.flow.chooseBranch(branch);
    }
}

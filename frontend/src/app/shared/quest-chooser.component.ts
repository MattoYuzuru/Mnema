import { ChangeDetectionStrategy, Component, ElementRef, computed, input, output, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

import { onboardingText } from '../core/onboarding/onboarding-texts';
import { QuestAction, QuestDeckOption, QuestNotice, QuestPhase } from '../core/onboarding/quest.models';

let nextChooser = 0;

/**
 * What stands between a chevron of «Путь» and the first step of a side quest (OB-304): the choice of the deck the quest runs in (the
 * learner's own decks; the sample copy, stamped «Учебная», only where the quest may demonstrate on it), or the reason it cannot start with
 * what to do instead (the sample deck, a link), or the note that it is being prepared, or a failure with «Повторить». Never a dead end:
 * every state has «Не сейчас». The heading takes the focus (`focusHeading`). The host puts it in a modal dialog; the styleguide draws it
 * in the flow.
 */
@Component({
    selector: 'app-quest-chooser',
    imports: [RouterLink],
    template: `
      <section class="chooser" [attr.aria-labelledby]="titleId">
        <h2 #heading [id]="titleId" tabindex="-1">{{ title() }}</h2>
        @switch (phase()) {
          @case ('choose') {
            <p>{{ lead }}</p>
            <fieldset>
              <legend class="visually-hidden">{{ legend }}</legend>
              @for (deck of decks(); track deck.deckId) {
                <label class="choice">
                  <input type="radio" [name]="groupName" [value]="deck.deckId" [checked]="deck.deckId === selected()" (change)="chosen.emit(deck.deckId)" />
                  <span class="name">{{ deck.title || sampleStamp }}@if (deck.sample && deck.title) { <span class="stamp">{{ sampleStamp }}</span> }</span>
                </label>
              }
            </fieldset>
            <div class="actions">
              <button type="button" class="button quiet" (click)="cancelled.emit()">{{ cancel }}</button>
              <button type="button" class="button primary" [disabled]="selected() === null" (click)="started.emit()">{{ start }}</button>
            </div>
          }
          @case ('blocked') {
            @if (notice(); as current) {
              <p>{{ current.text }}</p>
              <div class="actions">
                <button type="button" class="button quiet" (click)="cancelled.emit()">{{ cancel }}</button>
                @for (action of current.actions; track action.label) {
                  @if (linkOf(action); as link) {
                    <a class="button" [routerLink]="link" (click)="acted.emit(action)">{{ action.label }}</a>
                  } @else {
                    <button type="button" class="button primary" (click)="acted.emit(action)">{{ action.label }}</button>
                  }
                }
              </div>
            }
          }
          @case ('error') {
            <p role="alert">{{ error }}</p>
            <div class="actions">
              <button type="button" class="button quiet" (click)="cancelled.emit()">{{ cancel }}</button>
              <button type="button" class="button primary" (click)="retried.emit()">{{ retry }}</button>
            </div>
          }
          @default {
            <p role="status">{{ loading }}</p>
            <div class="actions"><button type="button" class="button quiet" (click)="cancelled.emit()">{{ cancel }}</button></div>
          }
        }
      </section>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .chooser { display: grid; gap: .9rem; color: var(--mn-body); }
      h2 { margin: 0; color: var(--mn-ink); font: 600 1.6rem/1.1 var(--mn-font-display); overflow-wrap: anywhere; }
      h2:focus-visible { outline: none; }
      p { margin: 0; }
      fieldset { display: grid; gap: .25rem; margin: 0; padding: 0; border: 0; min-inline-size: 0; }
      .choice { display: flex; gap: .75rem; align-items: center; min-block-size: var(--mn-touch-min); border: 1px solid var(--mn-rule); padding: .4rem .75rem; background: var(--mn-sheet); cursor: pointer; }
      .choice:has(input:checked) { border-color: var(--mn-ink); background: var(--mn-soft); }
      .choice input { inline-size: 1.25rem; block-size: 1.25rem; margin: 0; accent-color: var(--mn-ink); }
      .choice input:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: 2px; }
      .name { display: inline-flex; flex-wrap: wrap; gap: .5rem; align-items: baseline; min-inline-size: 0; color: var(--mn-ink); font-weight: 650; overflow-wrap: anywhere; }
      .actions { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: space-between; align-items: center; border-block-start: 1px solid var(--mn-rule); padding-block-start: .75rem; }
      @media (forced-colors: active) { .choice, .choice:has(input:checked) { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class QuestChooserComponent {
    /** The milestone's title: the quest has no title of its own. */
    readonly title = input.required<string>();
    readonly phase = input<QuestPhase>('loading');
    readonly decks = input<readonly QuestDeckOption[]>([]);
    readonly selected = input<string | null>(null);
    readonly notice = input<QuestNotice | null>(null);
    readonly chosen = output<string>();
    readonly started = output<void>();
    readonly cancelled = output<void>();
    readonly acted = output<QuestAction>();
    readonly retried = output<void>();

    protected readonly lead = onboardingText('quest.choose.lead');
    protected readonly legend = onboardingText('quest.choose.legend');
    protected readonly start = onboardingText('quest.start');
    protected readonly cancel = onboardingText('quest.cancel');
    protected readonly loading = onboardingText('quest.loading');
    protected readonly error = onboardingText('quest.error');
    protected readonly retry = onboardingText('path.retry');
    protected readonly sampleStamp = onboardingText('sample.stamp');
    private readonly id = nextChooser++;
    protected readonly titleId = `mn-quest-${this.id}-title`;
    protected readonly groupName = `mn-quest-${this.id}-deck`;
    private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    protected readonly hasNotice = computed(() => this.notice() !== null);

    /** The router address of a link action; `null` for the sample deck (a button). */
    protected linkOf(action: QuestAction): readonly string[] | null {
        return action.kind === 'link' ? action.commands : null;
    }

    focusHeading(): void {
        this.heading().nativeElement.focus({ preventScroll: true });
    }
}

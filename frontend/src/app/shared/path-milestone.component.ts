import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { onboardingText } from '../core/onboarding/onboarding-texts';
import { MilestoneKey, MilestoneState } from '../core/onboarding/path.models';
import { PathProgressComponent } from './path-progress.component';

/** A milestone whose partial state is a stage, not a count: «сохранена, осталось разобрать» (target 2 for the two stages). */
const STAGED: readonly MilestoneKey[] = ['CAPTURE_NOTE'];

/**
 * One milestone of «Путь Mnema»: the state glyph with its word on the left (done, partial, not started: shape and word, never colour
 * alone), the title and the line of benefit, a thin progress bar with its text for a counted milestone that is under way, and for a
 * milestone not yet done a chevron that stretches over the whole row (a link named «Перейти: {title}»; the lavender fill on hover and
 * focus is `--mn-path-hover`). A staged milestone (`CAPTURE_NOTE`) says its stage in place of the benefit.
 *
 * The link has a real `href` (`href`: where a plain click of the browser would go; opens in a new tab as any link does) and reports a
 * plain click through `follow`, which the page turns into the navigation with the in-place hint; modified clicks are left to the browser.
 * With no `href` there is no chevron (the milestone has no place to point at).
 */
@Component({
    selector: 'app-path-milestone',
    imports: [PathProgressComponent],
    template: `
      <div class="milestone" [attr.data-state]="state()" [class.preview-hover]="preview() === 'hover'">
        <span class="state">
          @switch (state()) {
            @case ('DONE') {
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="9" fill="currentColor" /><path d="m7.5 12.5 3 3 6-6.5" class="tick" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
            }
            @case ('PARTIAL') {
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.5" /><path d="M12 3a9 9 0 0 1 0 18Z" fill="currentColor" /></svg>
            }
            @default {
              <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="2.4 3.2" /></svg>
            }
          }
          <span class="word">{{ stateWord() }}</span>
        </span>
        <div class="copy">
          <h3>{{ title() }}</h3>
          <p class="benefit">{{ benefit() }}</p>
          @if (counted()) {
            <div class="bar">
              <app-path-progress [label]="title()" [value]="progress()" [max]="target()" [valueText]="counter()" />
              <small aria-hidden="true">{{ counter() }}</small>
            </div>
          }
        </div>
        @if (linked()) {
          <a class="go" [attr.href]="href()" [attr.aria-label]="goLabel()" (click)="onClick($event)">
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m9 5 7 7-7 7" /></svg>
          </a>
        } @else { <span></span> }
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .milestone { position: relative; display: grid; grid-template-columns: 3.25rem minmax(0, 1fr) auto; gap: .25rem .9rem; align-items: center; border-block-start: 1px solid var(--mn-rule); padding: .85rem .5rem .85rem 0; }
      .state { display: grid; justify-items: center; gap: .15rem; color: var(--mn-ink); font: 700 .62rem/1.1 var(--mn-font-mono); text-transform: uppercase; letter-spacing: .04em; text-align: center; overflow-wrap: anywhere; }
      .state svg { inline-size: 1.4rem; block-size: 1.4rem; }
      .tick { stroke: var(--mn-on-ink); }
      .milestone[data-state='TODO'] .state { color: var(--mn-muted); }
      h3 { margin: 0; color: var(--mn-ink); font: 650 1rem/1.35 var(--mn-font-body); overflow-wrap: break-word; }
      .milestone[data-state='DONE'] h3 { color: var(--mn-body); }
      .benefit { margin: 0; color: var(--mn-muted); font-size: .92rem; }
      .bar { display: flex; gap: .5rem; align-items: center; margin-block-start: .35rem; }
      .bar app-path-progress { flex: 0 1 8rem; min-inline-size: 3rem; }
      .bar small { color: var(--mn-ink); font-weight: 650; white-space: nowrap; }
      .go { display: grid; place-items: center; inline-size: var(--mn-touch-min); block-size: var(--mn-touch-min); border-radius: 50%; color: var(--mn-ink); text-decoration: none; }
      /* The link covers the row: the whole milestone is its target. */
      .go::after { content: ''; position: absolute; inset: 0; }
      .go svg { inline-size: 1.25rem; block-size: 1.25rem; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
      .milestone:is(:has(.go:is(:hover, :focus-visible)), .preview-hover) { background: linear-gradient(to left, var(--mn-path-hover), transparent 70%); }
      .milestone:is(:has(.go:is(:hover, :focus-visible)), .preview-hover) .go { background: var(--mn-path-hover); }
      .go:focus-visible { outline: 3px solid var(--mn-focus); outline-offset: -3px; }
      /* A phone: the state column and the gaps give way to the title, the chevron keeps its 44 px touch target. */
      @media (max-width: 24rem) {
        .milestone { grid-template-columns: 2.75rem minmax(0, 1fr) auto; column-gap: .6rem; padding-inline-end: 0; }
        .state { font-size: .58rem; }
      }
      @media (forced-colors: active) {
        .state, .go, h3, .bar small { color: CanvasText; }
        .tick { stroke: Canvas; }
        .milestone:is(:has(.go:is(:hover, :focus-visible)), .preview-hover) { background: none; outline: 2px solid Highlight; outline-offset: -2px; }
        .go:focus-visible { outline-color: Highlight; }
      }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PathMilestoneComponent {
    readonly key = input.required<MilestoneKey>();
    readonly state = input.required<MilestoneState>();
    readonly progress = input(0);
    readonly target = input(1);
    /** Where the chevron links to; `null` for none (and always none for a milestone that is done). */
    readonly href = input<string | null>(null);
    /** The styleguide shows the hover look of the row without a pointer; the product never sets it. */
    readonly preview = input<'hover' | null>(null);
    /** A plain click on the chevron: the page goes there with the in-place hint. */
    readonly follow = output<void>();

    protected readonly title = computed(() => onboardingText(`m.${this.key()}`));
    /** The line of benefit; a staged milestone that is under way says its stage instead. */
    protected readonly benefit = computed(() => onboardingText(this.state() === 'PARTIAL' && STAGED.includes(this.key()) ? `m.${this.key()}.partial` as 'm.CAPTURE_NOTE.partial' : `m.${this.key()}.b`));
    /** A counted milestone that is under way shows its count and bar; a staged one shows its stage. */
    protected readonly counted = computed(() => this.state() === 'PARTIAL' && this.target() > 1 && !STAGED.includes(this.key()));
    protected readonly counter = computed(() => onboardingText('path.total', { done: this.progress(), total: this.target() }));
    protected readonly stateWord = computed(() => {
        switch (this.state()) {
            case 'DONE': return onboardingText('path.state.done');
            case 'TODO': return onboardingText('path.state.todo');
            default: return this.counted() ? this.counter() : onboardingText('path.state.partial');
        }
    });
    protected readonly linked = computed(() => this.state() !== 'DONE' && this.href() !== null);
    protected readonly goLabel = computed(() => onboardingText('path.go', { title: this.title() }));

    protected onClick(event: MouseEvent): void {
        // A new tab, a window, the context key: the browser's own link behaviour, without the hint.
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        this.follow.emit();
    }
}

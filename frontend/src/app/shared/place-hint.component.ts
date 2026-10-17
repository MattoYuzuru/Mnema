import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * A hint in its place, with no veil: a short sentence next to the thing it is about (the search of the catalogue after a minute of
 * browsing). A `note` for assistive technology; it never takes the focus and never moves anything, and it goes away with its owner.
 */
@Component({
    selector: 'app-place-hint',
    host: { role: 'note' },
    template: `<ng-content />`,
    styles: [`
      :host { position: relative; display: block; max-inline-size: 22rem; border: 1px solid var(--mn-ink); padding: .8rem 1rem; color: var(--mn-body); background: var(--mn-sheet); }
      :host::before { content: ''; position: absolute; inset-block-start: -.45rem; inset-inline-start: 1.5rem; inline-size: .8rem; block-size: .8rem;
        border-block-start: 1px solid var(--mn-ink); border-inline-start: 1px solid var(--mn-ink); background: var(--mn-sheet); transform: rotate(45deg); }
      @media (forced-colors: active) { :host, :host::before { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PlaceHintComponent {}

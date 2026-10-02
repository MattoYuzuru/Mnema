import { ChangeDetectionStrategy, Component, input } from '@angular/core';

let nextToggletip = 0;

/**
 * A «ⓘ» button that opens a short explanation. The native Popover API provides the behaviour: `popovertarget` toggles
 * the popover, `popover="auto"` gives light dismiss and Esc, and the browser keeps focus on (or returns it to) the
 * button. Nothing depends on hover. The content is plain projected markup (text, a link); the accessible name of the
 * button is «Подробнее: <topic>». Not for errors or anything the learner must read: put those in the page flow.
 */
@Component({
    selector: 'app-toggletip',
    template: `
      <button type="button" class="trigger" [attr.popovertarget]="popoverId" [attr.aria-label]="'Подробнее: ' + topic()"
        [style.anchor-name]="anchorName">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
          <circle cx="12" cy="12" r="9.25" fill="none" stroke="currentColor" stroke-width="1.5" />
          <circle cx="12" cy="7.9" r="1.2" fill="currentColor" />
          <path d="M12 11v6" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" />
        </svg>
      </button>
      <div class="bubble" popover="auto" [id]="popoverId" [style.position-anchor]="anchorName"><ng-content /></div>
    `,
    styleUrl: './toggletip.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ToggletipComponent {
    /** What the explanation is about, for the accessible name: «Подробнее: <topic>». */
    readonly topic = input.required<string>();

    private readonly uid = `mn-toggletip-${nextToggletip++}`;
    protected readonly popoverId = `${this.uid}-popover`;
    protected readonly anchorName = `--${this.uid}`;
}

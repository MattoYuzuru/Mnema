import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { AuthorChipComponent } from '../../../shared/author-chip.component';
import { AccessIdentity } from './access-identities';
import { ACCESS_TEXT } from './access.text';

/**
 * A person of the panel: the author chip when the person published a profile, otherwise one plain sentence. Nothing here is a link
 * or a control, so the row's own actions are the only tab stops.
 */
@Component({
    selector: 'app-access-person',
    imports: [AuthorChipComponent],
    template: `
      @switch (identity().state) {
        @case ('card') {
          @if (card(); as person) { <app-author-chip [username]="person.username" [avatarSrc]="person.avatarSrc" /> }
        }
        @case ('none') { <span class="plain">{{ text.noProfile }}</span> }
        @case ('failed') {
          <span class="plain">{{ text.nameFailed }}</span>
          <button type="button" class="button quiet small" [attr.aria-label]="text.nameRetryLabel" (click)="retry.emit()">{{ text.nameRetry }}</button>
        }
        @default { <span class="plain muted">{{ text.nameLoading }}</span> }
      }
    `,
    styles: [`
      :host { display: inline-flex; flex-wrap: wrap; align-items: center; gap: .25rem .5rem; min-inline-size: 0; max-inline-size: 100%; }
      button { min-block-size: var(--mn-touch-min); }
      .plain { color: var(--mn-ink); font-size: .9rem; line-height: 1.2; overflow-wrap: anywhere; }
      .muted { color: var(--mn-muted); }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessPersonComponent {
    readonly identity = input.required<AccessIdentity>();
    /** «Повторить» after a failed lookup of the name. */
    readonly retry = output<void>();

    protected readonly text = ACCESS_TEXT;
    protected readonly card = computed(() => {
        const identity = this.identity();
        return identity.state === 'card' ? identity : null;
    });
}

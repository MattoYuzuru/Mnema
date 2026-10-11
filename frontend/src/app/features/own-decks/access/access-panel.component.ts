import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

import { AccessPeopleComponent } from './access-people.component';
import { AccessRequestsComponent } from './access-requests.component';
import { InviteLinksComponent } from './invite-links.component';

/**
 * Who gets in to an «По приглашению» deck: the open requests, the invitation links and the people who already have access (with
 * the invitation by login). The three lists are separate sections of one panel, each with its own heading, loading state and retry,
 * so one failing request never hides the others. Approving a request re-reads the people list.
 */
@Component({
    selector: 'app-access-panel',
    imports: [AccessRequestsComponent, InviteLinksComponent, AccessPeopleComponent],
    template: `
      <div class="access-panel">
        <app-access-requests [deckId]="deckId()" [requestsEnabled]="requestsEnabled()" (approved)="peopleToken.update(token => token + 1)" />
        <app-invite-links [deckId]="deckId()" [deckTitle]="deckTitle()" />
        <app-access-people [deckId]="deckId()" [reloadToken]="peopleToken()" />
      </div>
    `,
    styles: [`
      :host { display: block; min-inline-size: 0; }
      .access-panel { display: grid; gap: var(--mn-space-5); min-inline-size: 0; border-block-start: 1px solid var(--mn-rule); padding-block-start: var(--mn-space-4); }
      @media (forced-colors: active) { .access-panel { border-color: CanvasText; } }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessPanelComponent {
    readonly deckId = input.required<string>();
    readonly deckTitle = input('Колода');
    readonly requestsEnabled = input(true);

    protected readonly peopleToken = signal(0);
}

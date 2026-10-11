import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AuthorCardsService } from '../../../author-cards.service';
import { AutoLoadComponent } from '../../../shared/auto-load.component';
import { focusNearestAction } from './access-focus';
import { AccessIdentities } from './access-identities';
import { AccessList } from './access-list';
import { AccessPersonComponent } from './access-person.component';
import { AccessApiService } from './access-api.service';
import { AccessRequest } from './access.models';
import { ACCESS_TEXT, accessProblemText } from './access.text';
import { accessFailureOf } from './access.models';

let nextRequests = 0;

/**
 * «Запросы доступа»: the open requests of the deck, newest first, each with the person (an author chip, or «Пользователь без
 * публичного профиля») and the two decisions. «Принять» opens the grant and tells the person; «Отклонить» tells nobody. A decided
 * row leaves the list, the result is spoken through a polite status, and the focus moves to the neighbouring row's first action
 * (or the heading when the list is empty). One decision runs at a time; the other buttons say so with `aria-disabled`.
 */
@Component({
    selector: 'app-access-requests',
    imports: [DatePipe, AutoLoadComponent, AccessPersonComponent],
    templateUrl: './access-requests.component.html',
    styleUrl: './access-section.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessRequestsComponent {
    readonly deckId = input.required<string>();
    /** The «Принимать запросы» switch: when off, a note says that new requests do not arrive (open ones can still be decided). */
    readonly requestsEnabled = input(true);
    /** A request was approved: the person now has access, the people list re-reads. */
    readonly approved = output<void>();

    protected readonly text = ACCESS_TEXT;
    protected readonly uid = `mn-access-requests-${nextRequests++}`;
    protected readonly status = signal('');
    protected readonly problem = signal<string | null>(null);
    /** The decision that is on its way. */
    protected readonly busy = signal<{ readonly requesterId: string; readonly decision: 'approve' | 'reject' } | null>(null);
    protected readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

    private readonly api = inject(AccessApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    protected readonly identities = new AccessIdentities(inject(AuthorCardsService));
    protected readonly list = new AccessList<AccessRequest>(
        cursor => this.api.requests(this.deckId(), cursor), request => request.requesterId, this.destroyRef, ACCESS_TEXT.moreError);

    constructor() {
        effect(() => {
            this.deckId();
            untracked(() => this.list.reload());
        });
        effect(() => {
            const ids = this.list.items().map(request => request.requesterId);
            untracked(() => this.identities.resolve(ids));
        });
    }

    protected reload(): void {
        this.list.reload();
    }

    protected decide(request: AccessRequest, decision: 'approve' | 'reject'): void {
        if (this.busy() !== null) return;
        const rows = this.list.items();
        const index = rows.findIndex(row => row.requesterId === request.requesterId);
        const person = this.identities.of(request.requesterId);
        this.busy.set({ requesterId: request.requesterId, decision });
        this.problem.set(null);
        this.status.set('');
        this.api.decide(this.deckId(), request.requesterId, decision).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: () => {
                this.busy.set(null);
                this.list.remove(request.requesterId);
                this.status.set(decision === 'approve'
                    ? ACCESS_TEXT.approved(person.state === 'card' ? `@${person.username}` : ACCESS_TEXT.noProfile.toLowerCase())
                    : ACCESS_TEXT.rejected);
                if (decision === 'approve') this.approved.emit();
                this.focusNeighbour(index);
            },
            error: (error: unknown) => {
                this.busy.set(null);
                const failure = accessFailureOf(error);
                this.problem.set(accessProblemText(failure));
                // A request that is already decided or gone (404): the list is stale.
                if (failure?.status === 404) this.list.reload();
            }
        });
    }

    protected busyOn(request: AccessRequest, decision: 'approve' | 'reject'): boolean {
        const busy = this.busy();
        return busy !== null && busy.requesterId === request.requesterId && busy.decision === decision;
    }

    protected rowId(request: AccessRequest): string {
        return `${this.uid}-${request.requesterId}`;
    }

    private focusNeighbour(index: number): void {
        afterNextRender(() => focusNearestAction(this.host.nativeElement, index, this.heading().nativeElement), { injector: this.injector });
    }
}

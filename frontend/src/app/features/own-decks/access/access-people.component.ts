import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { AuthorCardsService } from '../../../author-cards.service';
import { AutoLoadComponent } from '../../../shared/auto-load.component';
import { AccessApiService } from './access-api.service';
import { focusNearestAction } from './access-focus';
import { AccessIdentities } from './access-identities';
import { AccessList } from './access-list';
import { AccessPersonComponent } from './access-person.component';
import { AccessGrant, LOGIN_PATTERN, accessFailureOf, normalizeLogin } from './access.models';
import { ACCESS_TEXT, accessProblemText } from './access.text';

let nextPeople = 0;

/**
 * «Кто имеет доступ» and «Пригласить по логину». The list shows the people who hold a grant (newest first) as author chips; «Закрыть
 * доступ» closes it at once (copies the person already made stay). The login field adds a person with a public profile: the server
 * answers one and the same «not found» for an unknown login and a hidden profile, and so does the sentence here.
 */
@Component({
    selector: 'app-access-people',
    imports: [DatePipe, AutoLoadComponent, AccessPersonComponent],
    templateUrl: './access-people.component.html',
    styleUrl: './access-section.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessPeopleComponent {
    readonly deckId = input.required<string>();
    /** Bump to re-read the list (a request was approved elsewhere in the panel). */
    readonly reloadToken = input(0);

    protected readonly text = ACCESS_TEXT;
    protected readonly uid = `mn-access-people-${nextPeople++}`;
    protected readonly login = signal('');
    protected readonly granting = signal(false);
    protected readonly loginProblem = signal<string | null>(null);
    protected readonly status = signal('');
    protected readonly problem = signal<string | null>(null);
    protected readonly closing = signal<string | null>(null);
    protected readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    protected readonly loginField = viewChild<ElementRef<HTMLInputElement>>('loginField');

    private readonly api = inject(AccessApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    protected readonly identities = new AccessIdentities(inject(AuthorCardsService));
    protected readonly list = new AccessList<AccessGrant>(
        cursor => this.api.grants(this.deckId(), cursor), grant => grant.granteeId, this.destroyRef, ACCESS_TEXT.moreError);

    constructor() {
        effect(() => {
            this.deckId();
            this.reloadToken();
            untracked(() => this.list.reload());
        });
        effect(() => {
            const ids = this.list.items().map(grant => grant.granteeId);
            untracked(() => this.identities.resolve(ids));
        });
    }

    protected reload(): void {
        this.list.reload();
    }

    protected onLogin(event: Event): void {
        this.login.set((event.target as HTMLInputElement).value);
        this.loginProblem.set(null);
    }

    protected invite(): void {
        if (this.granting()) return;
        const login = normalizeLogin(this.login());
        if (!LOGIN_PATTERN.test(login)) {
            this.loginProblem.set(ACCESS_TEXT.loginInvalid);
            afterNextRender(() => this.loginField()?.nativeElement.focus(), { injector: this.injector });
            return;
        }
        this.granting.set(true);
        this.loginProblem.set(null);
        this.status.set('');
        this.api.grantByLogin(this.deckId(), login).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: () => {
                this.granting.set(false);
                this.login.set('');
                this.status.set(ACCESS_TEXT.loginGranted(login));
                this.list.reload();
            },
            error: (error: unknown) => {
                this.granting.set(false);
                const failure = accessFailureOf(error);
                this.loginProblem.set(failure?.status === 404 ? ACCESS_TEXT.loginNotFound
                    : failure?.status === 400 ? (failure.code === 'PUBLICATION_REQUIRED' ? ACCESS_TEXT.loginNeedsInvite : ACCESS_TEXT.loginSelf)
                    : failure?.status === 429 ? ACCESS_TEXT.loginBusy
                    : failure?.status === 503 ? ACCESS_TEXT.loginUnavailable
                    : accessProblemText(failure));
                afterNextRender(() => this.loginField()?.nativeElement.focus(), { injector: this.injector });
            }
        });
    }

    protected revoke(grant: AccessGrant): void {
        if (this.closing() !== null) return;
        const index = this.list.items().findIndex(row => row.granteeId === grant.granteeId);
        this.closing.set(grant.granteeId);
        this.problem.set(null);
        this.status.set('');
        this.api.revokeGrant(this.deckId(), grant.granteeId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: () => {
                this.closing.set(null);
                this.list.remove(grant.granteeId);
                this.status.set(ACCESS_TEXT.accessClosed);
                this.focusNeighbour(index);
            },
            error: (error: unknown) => {
                this.closing.set(null);
                this.problem.set(accessProblemText(accessFailureOf(error)));
            }
        });
    }

    protected rowId(grant: AccessGrant): string {
        return `${this.uid}-${grant.granteeId}`;
    }

    private focusNeighbour(index: number): void {
        afterNextRender(() => focusNearestAction(this.host.nativeElement, index, this.heading().nativeElement), { injector: this.injector });
    }
}

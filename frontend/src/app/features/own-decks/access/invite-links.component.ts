import { DatePipe, formatDate } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, LOCALE_ID, ElementRef, Injector, afterNextRender, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { MnemaSelectComponent, MnemaSelectOption } from '../../../core/controls/mnema-select.component';
import { ToastService } from '../../../core/notifications/toast.service';
import { AutoLoadComponent } from '../../../shared/auto-load.component';
import { ShareButtonComponent } from '../../../shared/share-button.component';
import { AccessApiService } from './access-api.service';
import { AccessList } from './access-list';
import { ACTIVE_INVITES_LIMIT, CreatedInvite, DeckInvite, absoluteInviteLink, accessFailureOf } from './access.models';
import { ACCESS_TEXT, accessProblemText, expiryOption, inviteStatusWord, inviteUsesText, usesOption } from './access.text';

let nextInvites = 0;

/** The use limits offered (`null`: unlimited) and the expiries in days (`null`: never), as the select values. */
const USES_CHOICES: readonly (number | null)[] = [1, 5, 20, 100, null];
const EXPIRY_CHOICES: readonly (number | null)[] = [1, 7, 30, 90, null];
const choiceValue = (choice: number | null): string => choice === null ? 'none' : String(choice);
const choiceOf = (value: string): number | null => value === 'none' ? null : Number(value);

/**
 * «Пригласительные ссылки»: create a link (how many times it opens and how long it lives), see the ones already made and revoke an
 * active one. The token exists only in the answer of the creation: the new link is shown once, in place of the form, with «Поделиться»
 * and the sentence that it cannot be shown again; «Готово» drops it from memory and brings the form back. The list never holds a
 * token. A lost link is revoked and replaced.
 */
@Component({
    selector: 'app-invite-links',
    imports: [DatePipe, MnemaSelectComponent, ShareButtonComponent, AutoLoadComponent],
    templateUrl: './invite-links.component.html',
    styleUrls: ['./access-section.css', './invite-links.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class InviteLinksComponent {
    readonly deckId = input.required<string>();
    readonly deckTitle = input('Колода');

    protected readonly text = ACCESS_TEXT;
    protected readonly uid = `mn-invites-${nextInvites++}`;
    protected readonly usesOptions: readonly MnemaSelectOption[] = USES_CHOICES.map(choice => ({ value: choiceValue(choice), label: usesOption(choice) }));
    protected readonly expiryOptions: readonly MnemaSelectOption[] = EXPIRY_CHOICES.map(choice => ({ value: choiceValue(choice), label: expiryOption(choice) }));
    protected readonly uses = signal(choiceValue(1));
    protected readonly expiry = signal(choiceValue(7));
    protected readonly creating = signal(false);
    protected readonly createProblem = signal<string | null>(null);
    /** The link just created. Held in memory only until «Готово»; never stored, never logged. */
    protected readonly created = signal<{ readonly invite: CreatedInvite['invite']; readonly url: string } | null>(null);
    protected readonly revoking = signal<string | null>(null);
    protected readonly problem = signal<string | null>(null);
    protected readonly status = signal('');
    protected readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
    protected readonly createdGroup = viewChild<ElementRef<HTMLElement>>('createdGroup');
    protected readonly createButton = viewChild<ElementRef<HTMLButtonElement>>('createButton');

    private readonly locale = inject(LOCALE_ID);
    private readonly api = inject(AccessApiService);
    private readonly toasts = inject(ToastService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    protected readonly list = new AccessList<DeckInvite>(
        cursor => this.api.invites(this.deckId(), cursor), invite => invite.inviteId, this.destroyRef, ACCESS_TEXT.moreError);

    constructor() {
        effect(() => {
            this.deckId();
            untracked(() => this.list.reload());
        });
    }

    protected reload(): void {
        this.list.reload();
    }

    protected create(): void {
        if (this.creating()) return;
        this.creating.set(true);
        this.createProblem.set(null);
        this.status.set('');
        this.api.createInvite(this.deckId(), { maxUses: choiceOf(this.uses()), expiresInDays: choiceOf(this.expiry()) })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: answer => {
                    this.creating.set(false);
                    this.created.set({ invite: answer.invite, url: absoluteInviteLink(answer.link, window.location.origin) });
                    this.toasts.echo(ACCESS_TEXT.newInviteCreated);
                    this.list.reload();
                    afterNextRender(() => this.createdGroup()?.nativeElement.focus(), { injector: this.injector });
                },
                error: (error: unknown) => {
                    this.creating.set(false);
                    const failure = accessFailureOf(error);
                    // A refused creation made nothing; a lost answer may have made a link nobody has seen: the list shows it.
                    if (failure === null || failure.status === 0 || failure.status >= 500) this.list.reload();
                    this.createProblem.set(failure?.status === 400 && failure.code === 'PUBLICATION_REQUIRED' ? ACCESS_TEXT.inviteNeedsLevel
                        : failure?.status === 422 ? (failure.limit === ACTIVE_INVITES_LIMIT ? ACCESS_TEXT.inviteLimit : ACCESS_TEXT.inviteStorageFull)
                        : failure === null || failure.status === 0 ? ACCESS_TEXT.inviteNetwork
                        : accessProblemText(failure));
                }
            });
    }

    /** «Готово»: the link leaves the memory of the page and the form comes back. */
    protected dismissCreated(): void {
        this.created.set(null);
        afterNextRender(() => this.createButton()?.nativeElement.focus(), { injector: this.injector });
    }

    protected revoke(invite: DeckInvite): void {
        if (this.revoking() !== null) return;
        this.revoking.set(invite.inviteId);
        this.problem.set(null);
        this.status.set('');
        this.api.revokeInvite(this.deckId(), invite.inviteId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: revoked => {
                this.revoking.set(null);
                this.list.replace(revoked);
                this.status.set(ACCESS_TEXT.revoked);
                // The revoked row has no action left: the focus goes to the section heading, never to a neighbouring «Отозвать».
                afterNextRender(() => this.heading().nativeElement.focus(), { injector: this.injector });
            },
            error: (error: unknown) => {
                this.revoking.set(null);
                this.problem.set(accessProblemText(accessFailureOf(error)));
            }
        });
    }

    /** «Отозвать» with the link it is about, named by its creation time (the list has no other identity to read). */
    protected revokeLabel(invite: DeckInvite): string {
        return `${ACCESS_TEXT.revoke} ссылку, созданную ${formatDate(invite.createdAt, 'dd.MM.y, HH:mm', this.locale)}`;
    }

    protected facts(invite: DeckInvite): string {
        return inviteUsesText(invite);
    }

    protected word(invite: DeckInvite): string {
        return inviteStatusWord(invite.status);
    }

    protected shown(url: string): string {
        return url.replace(/^https?:\/\//, '');
    }
}

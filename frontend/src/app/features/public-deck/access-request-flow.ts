import { DestroyRef, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';

import { AccessApiService } from '../own-decks/access/access-api.service';
import { accessFailureOf } from '../own-decks/access/access.models';
import { PUBLIC_ACTION_TEXT, longWaitText } from './public-deck.text';
import { publicFailureOf } from './public-deck.models';

export type AccessRequestState = 'idle' | 'sending' | 'sent' | 'withdrawing';

/** What the screen must do when the request changes more than its own state. */
export interface AccessRequestHooks {
    /** The request was just sent (the screen puts the focus on the sentence that says so). */
    sent(): void;
    /** 404: the code is gone (rotated or closed); the screen becomes «Колода не найдена». */
    gone(): void;
    /** The request was taken back (the screen puts the focus on «Запросить доступ», which replaced the button that was pressed). */
    withdrawn(): void;
    /** 400: there is nothing to ask for (the viewer can read the deck now); the screen reads the deck again. */
    readable(): void;
}

/**
 * «Запросить доступ» and «Отменить запрос» of the invitation-only screen (`contracts/decks/access.json`). Whatever the author
 * decides, the answer to a request is always the same calm sentence: a first request, a repeat and a request the author refused
 * silently look alike, and so does an author who turned requests off (403). A request is never confirmed by the screen as accepted.
 */
export class AccessRequestFlow {
    readonly state = signal<AccessRequestState>('idle');
    /** A status sentence for what just happened without a problem («Запрос отменён.»); spoken politely. */
    readonly notice = signal<string | null>(null);
    /** A plain sentence for an attempt that failed (a limit, a wait, a lost connection); spoken at once, drawn as an error notice. */
    readonly error = signal<string | null>(null);

    constructor(
        private readonly api: AccessApiService,
        private readonly destroyRef: DestroyRef,
        private readonly hooks: AccessRequestHooks
    ) {}

    reset(): void {
        this.state.set('idle');
        this.notice.set(null);
        this.error.set(null);
    }

    send(code: string): void {
        if (this.state() !== 'idle') return;
        this.state.set('sending');
        this.notice.set(null);
        this.error.set(null);
        this.run(() => this.api.requestAccess(code), {
            next: () => this.sent(),
            error: error => {
                const failure = accessFailureOf(error);
                // Only «the author turned requests off» (DECK_INVITE_ONLY, or an empty 403) is answered like a sent request; any other 403
                // (a token without scope, for example) is a failure the viewer must hear about.
                if (failure?.status === 403 && (failure.code === null || failure.code === 'DECK_INVITE_ONLY')) return this.sent();
                this.state.set('idle');
                if (failure?.status === 404) this.hooks.gone();
                else if (failure?.status === 400) this.hooks.readable();
                else if (failure?.status === 422) this.error.set(PUBLIC_ACTION_TEXT.requestLimit);
                else if (failure?.status === 429) this.error.set(`Слишком много запросов. ${longWaitText(publicFailureOf(error).retryAfter)}`);
                else this.error.set(PUBLIC_ACTION_TEXT.requestFailed);
            }
        });
    }

    withdraw(code: string): void {
        if (this.state() !== 'sent') return;
        this.state.set('withdrawing');
        this.notice.set(null);
        this.error.set(null);
        this.run(() => this.api.withdrawRequest(code), {
            next: () => {
                this.state.set('idle');
                this.notice.set(PUBLIC_ACTION_TEXT.requestWithdrawn);
                this.hooks.withdrawn();
            },
            error: error => {
                if (accessFailureOf(error)?.status === 404) {
                    this.state.set('idle');
                    this.hooks.gone();
                    return;
                }
                this.state.set('sent');
                this.error.set(PUBLIC_ACTION_TEXT.requestWithdrawFailed);
            }
        });
    }

    private sent(): void {
        this.state.set('sent');
        this.notice.set(null);
        this.error.set(null);
        this.hooks.sent();
    }

    private run<T>(start: () => Observable<T>, observer: { next: () => void; error: (error: unknown) => void }): void {
        let source: Observable<T>;
        try {
            source = start();
        } catch (error) {
            observer.error(error);
            return;
        }
        source.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(observer);
    }
}

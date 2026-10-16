import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, Injector, computed, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { OnboardingApiService, validateJourneyCommand, validatePreferencesCommand } from './onboarding-api.service';
import {
    ActionOptions,
    JourneyAction,
    JourneyCommand,
    OnboardingProtocolError,
    OnboardingRead,
    OnboardingView,
    PreferenceChanges,
    PreferencesCommand,
    newCommandId
} from './onboarding.models';

/** Waits before the retries of a command whose outcome is unknown (no answer, a server error); the same command id is sent each time. */
export const COMMAND_RETRY_DELAYS_MS: readonly number[] = [400, 1_200];

export type JourneyError = 'load' | 'command';

/**
 * The account's onboarding state for the whole app: one `GET` per entry, then commands. Nothing here throws to the UI and nothing
 * waits on it for a page: a failure leaves {@link error} set and the tour absent («no tour»), never a blocked page.
 *
 * Commands run one at a time, each with the row version current when it is sent. A lost answer is retried with the **same**
 * command id (the server replays the stored response), so a step is never recorded twice. A stale version (412), a refused
 * transition or a switched-off feature (409) re-reads the view and resolves with it: the screen follows the server.
 */
@Injectable({ providedIn: 'root' })
export class JourneyStore {
    private readonly injector = inject(Injector);
    /** Resolved on first use, so a screen that only reads the journey (the goal question) needs no HTTP client of its own. */
    private get api(): OnboardingApiService { return this.injector.get(OnboardingApiService); }

    private readonly state = signal<OnboardingView | null>(null);
    readonly view = this.state.asReadonly();
    readonly journey = computed(() => this.state()?.journey ?? null);
    /** The feature flag, as the server last said it; `false` until the first answer. */
    readonly enabled = computed(() => this.state()?.enabled === true);
    readonly loading = signal(false);
    readonly error = signal<JourneyError | null>(null);

    private etag: string | null = null;
    private inflight: Promise<OnboardingView | null> | null = null;
    private tail: Promise<unknown> = Promise.resolve();

    /**
     * Reads the view. Once it is held this resolves with it without a request; `refresh` asks again with the held `ETag`, and a
     * `304` keeps the current view. Concurrent calls share one request. Resolves with the held view (`null` before any answer).
     */
    load(options: { readonly refresh?: boolean } = {}): Promise<OnboardingView | null> {
        if (this.inflight !== null) return this.inflight;
        if (this.state() !== null && options.refresh !== true) return Promise.resolve(this.state());
        return this.fetch(options.refresh === true ? this.etag : null);
    }

    /** One tour action. Resolves with the view the server answered with, or `null` when it could not be done (see {@link error}). */
    act(action: JourneyAction, options: ActionOptions = {}): Promise<OnboardingView | null> {
        return this.send(rowVersion => ({
            commandId: newCommandId(), rowVersion, action,
            ...(options.branch === undefined ? {} : { branch: options.branch }),
            ...(options.stepKey === undefined ? {} : { stepKey: options.stepKey })
        }), command => this.api.command(command), validateJourneyCommand);
    }

    /** The onboarding answers of the survey; `null` clears one. */
    setPreferences(changes: PreferenceChanges): Promise<OnboardingView | null> {
        return this.send(rowVersion => ({
            commandId: newCommandId(), rowVersion,
            ...(changes.experience === undefined ? {} : { experience: changes.experience }),
            ...(changes.weeklyTarget === undefined ? {} : { weeklyTarget: changes.weeklyTarget })
        }), command => this.api.preferences(command), validatePreferencesCommand);
    }

    /** Signing out: the next account starts with no view and no validator. */
    reset(): void {
        this.state.set(null);
        this.etag = null;
        this.error.set(null);
        this.loading.set(false);
        this.inflight = null;
    }

    private fetch(etag: string | null): Promise<OnboardingView | null> {
        this.loading.set(true);
        const request = (async (): Promise<OnboardingView | null> => {
            try {
                const result = await firstValueFrom(this.api.read(etag));
                if (result.kind === 'view') this.accept(result);
                else this.error.set(null);
            } catch {
                this.error.set('load');
            } finally {
                this.inflight = null;
                this.loading.set(false);
            }
            return this.state();
        })();
        this.inflight = request;
        return request;
    }

    private accept(read: OnboardingRead): void {
        this.state.set(read.view);
        this.etag = read.etag;
        this.error.set(null);
    }

    private send<C extends JourneyCommand | PreferencesCommand>(build: (rowVersion: string) => C, call: (command: C) => Observable<OnboardingRead>,
        validate: (command: C) => unknown): Promise<OnboardingView | null> {
        const run = async (): Promise<OnboardingView | null> => {
            const view = await this.load();
            const journey = view?.journey ?? null;
            if (view === null || !view.enabled || journey === null) return null;
            const command = build(journey.rowVersion);
            // A defect on our side is refused before any request and is not an unknown outcome to retry.
            validate(command);
            for (let attempt = 0; ; attempt++) {
                try {
                    const read = await firstValueFrom(call(command));
                    this.accept(read);
                    return read.view;
                } catch (error) {
                    if (staleOrRefused(error)) return this.reread();
                    const delay = COMMAND_RETRY_DELAYS_MS[attempt];
                    if (!outcomeUnknown(error) || delay === undefined) {
                        this.error.set('command');
                        return null;
                    }
                    await new Promise<void>(resolve => setTimeout(resolve, delay));
                }
            }
        };
        const result = this.tail.then(run, run);
        this.tail = result.catch(() => undefined);
        return result.catch(() => { this.error.set('command'); return null; });
    }

    /** The view after a conflict: asked again without a validator, because the held one is known to be stale. */
    private async reread(): Promise<OnboardingView | null> {
        const view = await (this.inflight ?? this.fetch(null));
        // The held view is known to be stale: when it could not be refreshed there is nothing to resolve with.
        return this.error() === 'load' ? null : view;
    }
}

/** 412 stale version, 428 missing version, 409 refused transition or feature off: the answer is the current view, not a retry. */
function staleOrRefused(error: unknown): boolean {
    return error instanceof HttpErrorResponse && (error.status === 412 || error.status === 428 || error.status === 409);
}

/** No answer reached the client, the server could not decide, or a success arrived malformed: the command may have been applied. */
function outcomeUnknown(error: unknown): boolean {
    if (error instanceof OnboardingProtocolError) return true;
    return error instanceof HttpErrorResponse && (error.status === 0 || error.status >= 500);
}

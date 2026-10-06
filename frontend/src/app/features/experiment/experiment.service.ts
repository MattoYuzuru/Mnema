import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';

import { appConfig } from '../../app.config';
import { AuthService } from '../../auth.service';

export type ExperimentEvent = 'EXPOSURE' | 'CONVERSION';

export const CONTROL = 'control';

/**
 * The A/B variants the server assigned to this account (they arrive with `GET /api/plans`; the page hands them over with
 * `adopt`) and the two events an experiment counts. The variant is the server's decision and never leaves the account;
 * the events carry only the experiment key, and the server counts them against the assigned variant. An event is sent at
 * most once per key and kind for the life of the session (a logout ends it: the next account in the same tab starts afresh), and a failure to send is silent: measuring must never get in
 * the way of the page.
 */
@Injectable({ providedIn: 'root' })
export class ExperimentService {
    private readonly http = inject(HttpClient);
    private readonly auth = inject(AuthService);
    private readonly url = `${appConfig.learningApiBaseUrl.replace(/\/$/u, '')}/experiment-events`;
    private readonly assigned = signal<Readonly<Record<string, string>>>({});
    private readonly sent = new Set<string>();
    private owner = this.account();
    private epoch = 0;

    constructor() {
        effect(() => {
            this.auth.status();
            this.auth.user();
            untracked(() => this.synchronize());
        });
    }

    /** Forgets the assignment and what was sent: the account that is gone must not silence the events of the next one. */
    private reset(): void {
        this.epoch++;
        this.assigned.set({});
        this.sent.clear();
    }

    adopt(variants: Readonly<Record<string, string>>): void {
        this.synchronize();
        this.assigned.set(variants);
    }

    /** The variant of {@code key}, or `control` when the experiment is not running for this account. */
    variant(key: string): string {
        this.synchronize();
        const variants = this.assigned();
        return Object.hasOwn(variants, key) ? variants[key] : CONTROL;
    }

    /** The experiment is running: only then do events mean anything. */
    running(key: string): boolean {
        this.synchronize();
        return Object.hasOwn(this.assigned(), key);
    }

    expose(key: string): void { this.send(key, 'EXPOSURE'); }

    convert(key: string): void { this.send(key, 'CONVERSION'); }

    private send(key: string, event: ExperimentEvent): void {
        const id = `${key}:${event}`;
        if (!this.running(key) || this.sent.has(id)) return;
        this.sent.add(id);
        const epoch = this.epoch;
        this.http.post(this.url, { key, event }).subscribe({ error: () => { if (epoch === this.epoch) this.sent.delete(id); } });
    }

    private account(): string | null { return this.auth.status() === 'authenticated' ? this.auth.user()?.accountId ?? null : null; }

    private synchronize(): void {
        const owner = this.account();
        if (owner !== this.owner) { this.owner = owner; this.reset(); }
    }
}

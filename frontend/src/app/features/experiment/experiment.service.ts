import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

import { appConfig } from '../../app.config';

export type ExperimentEvent = 'EXPOSURE' | 'CONVERSION';

export const CONTROL = 'control';

/**
 * The A/B variants the server assigned to this account (they arrive with `GET /api/plans`; the page hands them over with
 * `adopt`) and the two events an experiment counts. The variant is the server's decision and never leaves the account;
 * the events carry only the experiment key, and the server counts them against the assigned variant. An event is sent at
 * most once per key and kind for the life of the session, and a failure to send is silent: measuring must never get in
 * the way of the page.
 */
@Injectable({ providedIn: 'root' })
export class ExperimentService {
    private readonly http = inject(HttpClient);
    private readonly url = `${appConfig.learningApiBaseUrl.replace(/\/$/u, '')}/experiment-events`;
    private readonly assigned = signal<Readonly<Record<string, string>>>({});
    private readonly sent = new Set<string>();

    adopt(variants: Readonly<Record<string, string>>): void {
        this.assigned.set(variants);
    }

    /** The variant of {@code key}, or `control` when the experiment is not running for this account. */
    variant(key: string): string {
        return this.assigned()[key] ?? CONTROL;
    }

    /** The experiment is running: only then do events mean anything. */
    running(key: string): boolean {
        return key in this.assigned();
    }

    expose(key: string): void { this.send(key, 'EXPOSURE'); }

    convert(key: string): void { this.send(key, 'CONVERSION'); }

    private send(key: string, event: ExperimentEvent): void {
        const id = `${key}:${event}`;
        if (!this.running(key) || this.sent.has(id)) return;
        this.sent.add(id);
        this.http.post(this.url, { key, event }).subscribe({ error: () => this.sent.delete(id) });
    }
}

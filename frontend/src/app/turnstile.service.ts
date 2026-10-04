import { Injectable, inject } from '@angular/core';
import { HttpBackend, HttpClient } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import { BROWSER_IDENTITY_CONFIG } from './auth-browser';
import { objectValue } from './auth-protocol';

export class AbuseProtectionFailure extends Error {
    constructor(readonly code: 'unavailable' | 'retry') { super('abuse_protection_' + code); }
}

interface TurnstileApi {
    render(container: HTMLElement, options: Record<string, unknown>): string;
    execute(widget: string): void;
    remove(widget: string): void;
}
declare global { interface Window { turnstile?: TurnstileApi } }

/** Challenges are created per mutation, consumed once and kept only in memory. */
@Injectable({ providedIn: 'root' })
export class TurnstileService {
    private readonly http = new HttpClient(inject(HttpBackend));
    private readonly config = inject(BROWSER_IDENTITY_CONFIG);
    private loading: Promise<TurnstileApi> | null = null;

    async token(action: 'login' | 'register'): Promise<string | null> {
        const config = objectValue(await firstValueFrom(this.http.get<unknown>(
            `${this.config.authServerUrl}/api/accounts/abuse-protection`).pipe(timeout(8000))));
        if (config['mode'] === 'disabled') return null;
        if (config['mode'] !== 'required' || typeof config['siteKey'] !== 'string' ||
            !/^[A-Za-z0-9_-]{10,128}$/u.test(config['siteKey'])) throw new AbuseProtectionFailure('unavailable');
        const api = await this.load();
        const siteKey = config['siteKey'];
        return new Promise<string>((resolve, reject) => {
            const container = document.createElement('div');
            container.setAttribute('data-mnema-turnstile', '');
            document.body.append(container);
            let widget: string | undefined;
            let finished = false;
            const complete = (token?: string) => {
                if (finished) return;
                finished = true;
                clearTimeout(timer);
                try { if (widget !== undefined) api.remove(widget); }
                catch { /* DOM cleanup and promise settlement must survive an upstream API failure. */ }
                container.remove();
                if (token && token.length <= 2048) resolve(token);
                else reject(new AbuseProtectionFailure('retry'));
            };
            const timer = setTimeout(() => complete(), 20000);
            try {
                widget = api.render(container, { sitekey: siteKey, action, execution: 'execute', tabindex: -1,
                    'response-field': false, retry: 'never', 'refresh-expired': 'never',
                    callback: (value: string) => complete(value),
                    'error-callback': () => { complete(); return true; },
                    'expired-callback': () => complete(), 'timeout-callback': () => complete() });
                api.execute(widget);
            } catch { complete(); }
        });
    }

    private load(): Promise<TurnstileApi> {
        if (window.turnstile) return Promise.resolve(window.turnstile);
        this.loading ??= new Promise<TurnstileApi>((resolve, reject) => {
            const script = document.createElement('script');
            script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
            script.async = true;
            const fail = () => {
                clearTimeout(timer);
                script.remove();
                this.loading = null;
                reject(new AbuseProtectionFailure('unavailable'));
            };
            const timer = setTimeout(fail, 8000);
            script.onerror = fail;
            script.onload = () => {
                clearTimeout(timer);
                if (window.turnstile) resolve(window.turnstile);
                else fail();
            };
            document.head.append(script);
        });
        return this.loading;
    }
}

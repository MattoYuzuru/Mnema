import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BROWSER_IDENTITY_CONFIG } from './auth-browser';
import { AbuseProtectionFailure, TurnstileService } from './turnstile.service';

describe('one-use login protection', () => {
    const issuer = 'https://auth.fixture.test';
    let service: TurnstileService;
    let http: HttpTestingController;
    let options: Record<string, unknown>;
    let api: { render: ReturnType<typeof vi.fn>; execute: ReturnType<typeof vi.fn>; remove: ReturnType<typeof vi.fn> };
    const settle = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
    const callback = (name: string, value?: string) => (options[name] as (token?: string) => void)(value);

    beforeEach(() => {
        options = {};
        api = { render: vi.fn().mockImplementation((_element, value) => { options = value; return 'widget'; }),
            execute: vi.fn(), remove: vi.fn() };
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
            { provide: BROWSER_IDENTITY_CONFIG, useValue: { authServerUrl: issuer } }] });
        service = TestBed.inject(TurnstileService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => {
        http.verify();
        vi.useRealTimers();
        document.querySelectorAll('script[src^="https://challenges.cloudflare.com/"]').forEach(s => s.remove());
    });
    function config(mode = 'required', siteKey = '0xFixtureSiteKey'): void {
        http.expectOne(`${issuer}/api/accounts/abuse-protection`).flush({ mode, siteKey });
    }

    it('makes no Cloudflare request in local-disabled or legal-blocked mode', async () => {
        const local = service.token('login'); config('disabled'); expect(await local).toBeNull();
        const closed = service.token('register'); config('blocked');
        await expect(closed).rejects.toBeInstanceOf(AbuseProtectionFailure);
        expect(document.querySelector('script[src^="https://challenges.cloudflare.com/"]')).toBeNull();
    });

    it('rejects unknown modes and invalid site keys before script loading', async () => {
        for (const [mode, key] of [['unknown', '0xFixtureSiteKey'], ['required', 'bad-key\n']]) {
            const result = service.token('login'); config(mode, key);
            await expect(result).rejects.toBeInstanceOf(AbuseProtectionFailure);
        }
    });

    it('creates a fresh widget for each action and disposes it after one callback', async () => {
        vi.stubGlobal('turnstile', api);
        for (const action of ['register', 'login'] as const) {
            const result = service.token(action); config(); await settle();
            expect(options['action']).toBe(action);
            expect(options['execution']).toBe('execute');
            expect(options['tabindex']).toBe(-1);
            expect(options['response-field']).toBe(false);
            callback('callback', `fresh-${action}-token`);
            callback('callback', 'duplicate');
            expect(await result).toBe(`fresh-${action}-token`);
        }
        expect(api.render).toHaveBeenCalledTimes(2);
        expect(api.remove).toHaveBeenCalledTimes(2);
        expect(document.querySelector('body > div[data-mnema-turnstile]')).toBeNull();
    });

    it.each(['error-callback', 'expired-callback', 'timeout-callback'])('rejects %s and permits a new attempt', async name => {
        vi.stubGlobal('turnstile', api);
        const result = service.token('login'); const rejected = expect(result).rejects.toBeInstanceOf(AbuseProtectionFailure);
        config(); await settle(); callback(name); await rejected;
        const retry = service.token('login'); config(); await settle(); callback('callback', 'new-token');
        expect(await retry).toBe('new-token');
    });

    it('bounds waiting for an invisible challenge and rejects oversized tokens', async () => {
        vi.useFakeTimers(); vi.stubGlobal('turnstile', api);
        const result = service.token('login'); const rejected = expect(result).rejects.toBeInstanceOf(AbuseProtectionFailure);
        config(); await settle(); await vi.advanceTimersByTimeAsync(20000); await rejected;
        const oversized = service.token('login'); const rejectedSize = expect(oversized).rejects.toBeInstanceOf(AbuseProtectionFailure);
        config(); await settle(); callback('callback', 'x'.repeat(2049)); await rejectedSize;
    });

    it('loads the fixed HTTPS script explicitly and waits for its API', async () => {
        const result = service.token('login'); config(); await settle();
        const script = document.querySelector<HTMLScriptElement>('script[src^="https://challenges.cloudflare.com/"]')!;
        expect(script.src).toBe('https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit');
        expect(api.execute).not.toHaveBeenCalled();
        vi.stubGlobal('turnstile', api); script.dispatchEvent(new Event('load')); await settle();
        callback('callback', 'new-token'); expect(await result).toBe('new-token');
    });

    it('recovers from script blocking, a loaded script without API and script timeout', async () => {
        vi.useFakeTimers();
        for (const failure of ['error', 'load', 'timeout']) {
            const result = service.token('login'); const rejected = expect(result).rejects.toBeInstanceOf(AbuseProtectionFailure);
            config(); await settle();
            const script = document.querySelector<HTMLScriptElement>('script[src^="https://challenges.cloudflare.com/"]')!;
            if (failure === 'timeout') await vi.advanceTimersByTimeAsync(8000);
            else script.dispatchEvent(new Event(failure));
            await rejected;
            expect(document.querySelector('script[src^="https://challenges.cloudflare.com/"]')).toBeNull();
        }
    });

    it('cleans up a widget when rendering fails', async () => {
        vi.stubGlobal('turnstile', api); api.render.mockImplementation(() => { throw new Error('render failed'); });
        const result = service.token('login'); config();
        await expect(result).rejects.toBeInstanceOf(AbuseProtectionFailure);
        expect(document.querySelector('body > div[data-mnema-turnstile]')).toBeNull();
    });

    it.each(['success', 'error', 'timeout'])('settles %s even if the external remove API throws', async outcome => {
        vi.useFakeTimers(); vi.stubGlobal('turnstile', api);
        api.remove.mockImplementation(() => { throw new Error('upstream cleanup failed'); });
        const result = service.token('login');
        const assertion = outcome === 'success' ? expect(result).resolves.toBe('token')
            : expect(result).rejects.toBeInstanceOf(AbuseProtectionFailure);
        config(); await settle();
        if (outcome === 'success') callback('callback', 'token');
        else if (outcome === 'error') callback('error-callback');
        else await vi.advanceTimersByTimeAsync(20000);
        await assertion;
        expect(document.querySelector('body > div[data-mnema-turnstile]')).toBeNull();
    });
});

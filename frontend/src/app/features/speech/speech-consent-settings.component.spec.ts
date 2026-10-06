import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SpeechConsentSettingsComponent } from './speech-consent-settings.component';

describe('SpeechConsentSettingsComponent', () => {
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    function render() {
        const fixture = TestBed.createComponent(SpeechConsentSettingsComponent);
        fixture.detectChanges();
        return { fixture, root: fixture.nativeElement as HTMLElement, component: fixture.componentInstance };
    }

    it('offers withdrawal without a capability-gated read or recording', () => {
        const { root } = render();
        expect(root.querySelector<HTMLButtonElement>('button')?.disabled).toBe(false);
        expect(root.textContent).toContain('уже начатая обработка может завершиться');
        http.expectNone('/api/speech-consent');
    });

    it('withdraws by DELETE, announces the result and restores the same control', async () => {
        const { component, fixture, root } = render();
        const button = root.querySelector<HTMLButtonElement>('button')!;
        const result = component.withdraw();
        fixture.detectChanges();
        expect(button.getAttribute('aria-disabled')).toBe('true');
        const request = http.expectOne('/api/speech-consent');
        expect(request.request.method).toBe('DELETE');
        expect(request.request.body).toBeNull();
        request.flush(null, { status: 204, statusText: 'No Content' });
        await result;
        fixture.detectChanges();
        expect(button.disabled).toBe(false);
        expect(button.getAttribute('aria-disabled')).toBeNull();
        expect(root.querySelector('[role="status"]')?.textContent).toContain('Согласие на распознавание отозвано');
        expect(root.querySelector('button')).toBe(button);
    });

    it('prevents duplicate in-flight withdrawal and permits an idempotent repeat', async () => {
        const { component, fixture } = render();
        const first = component.withdraw();
        await component.withdraw();
        http.expectOne('/api/speech-consent').flush(null, { status: 204, statusText: 'No Content' });
        await first;
        fixture.detectChanges();
        const repeat = component.withdraw();
        http.expectOne('/api/speech-consent').flush(null, { status: 204, statusText: 'No Content' });
        await repeat;
    });

    it('keeps a retry available and never displays server detail when withdrawal fails', async () => {
        const { component, fixture, root } = render();
        const result = component.withdraw();
        http.expectOne('/api/speech-consent').flush({ detail: 'private failure detail' }, { status: 500, statusText: 'Server error' });
        await result;
        fixture.detectChanges();
        expect(root.querySelector('[role="alert"]')?.textContent).toContain('Не удалось отозвать');
        expect(root.textContent).not.toContain('private failure detail');
        expect(root.querySelector('[role="status"]')?.textContent?.trim()).toBe('');
        expect(root.querySelector<HTMLButtonElement>('button')?.disabled).toBe(false);
    });

    it('requires the successful status from the withdrawal contract', async () => {
        const { component, fixture, root } = render();
        const result = component.withdraw();
        http.expectOne('/api/speech-consent').flush({}, { status: 200, statusText: 'OK' });
        await result;
        fixture.detectChanges();
        expect(root.querySelector('[role="alert"]')).not.toBeNull();
        expect(root.querySelector('[role="status"]')?.textContent?.trim()).toBe('');
    });
});

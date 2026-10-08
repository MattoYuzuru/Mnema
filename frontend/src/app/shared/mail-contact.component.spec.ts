import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { LEGAL_OPERATOR, operatorMailto } from './legal-operator';
import { MAIL_NAVIGATOR } from './mail-navigator';
import { MailContactComponent } from './mail-contact.component';

describe('MailContactComponent', () => {
    function render(navigate = vi.fn()) {
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: MAIL_NAVIGATOR, useValue: navigate }] });
        const fixture = TestBed.createComponent(MailContactComponent);
        fixture.detectChanges();
        return { root: fixture.nativeElement as HTMLElement, navigate };
    }

    it('is a named, decorative-glyph button that does not print or embed the address', () => {
        const { root } = render();
        const button = root.querySelector('button')!;
        expect(button.type).toBe('button');
        expect(button.textContent!.trim()).toBe('Написать на почту');
        expect(button.querySelector('app-mail-glyph')?.getAttribute('aria-hidden')).toBe('true');
        expect(button.querySelector('svg')?.getAttribute('focusable')).toBe('false');
        expect(root.querySelector('a')).toBeNull();
        expect(root.innerHTML).not.toContain('@');
        expect(root.innerHTML).not.toContain(LEGAL_OPERATOR.email.split('@')[0]);
        expect(root.innerHTML).not.toContain('mailto');
        const live = root.querySelector('[aria-live="polite"]')!;
        expect(live.textContent!.trim()).toBe('');
    });

    it('announces where the address is printed once the action was used, without exposing it', () => {
        const navigate = vi.fn();
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: MAIL_NAVIGATOR, useValue: navigate }] });
        const fixture = TestBed.createComponent(MailContactComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        root.querySelector('button')!.click();
        fixture.detectChanges();
        const live = root.querySelector('[aria-live="polite"]')!;
        expect(live.textContent).toContain('Если почтовая программа не открылась');
        expect(live.querySelector('a')?.getAttribute('href')).toBe('/privacy#general');
        expect(root.innerHTML).not.toContain('@');
    });

    it('opens the operator mailto address only when activated', () => {
        const { root, navigate } = render();
        expect(navigate).not.toHaveBeenCalled();
        root.querySelector('button')!.click();
        expect(navigate).toHaveBeenCalledExactlyOnceWith(`mailto:${LEGAL_OPERATOR.email}`);
        expect(operatorMailto()).toBe(`mailto:${LEGAL_OPERATOR.email}`);
    });

    it('provides a browser navigator by default', () => {
        expect(typeof TestBed.inject(MAIL_NAVIGATOR)).toBe('function');
    });
});

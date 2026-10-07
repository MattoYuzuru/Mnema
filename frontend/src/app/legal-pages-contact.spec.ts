import { TestBed } from '@angular/core/testing';

import { PrivacyPageComponent } from './privacy-page.component';
import { TermsPageComponent } from './terms-page.component';
import { SUPPORT_CONTACT, telegramContact } from './shared/support-contact';

describe('Public legal-page contact', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [{ provide: SUPPORT_CONTACT, useValue: telegramContact('MnemaSupportBot') }] });
    });

    for (const page of [PrivacyPageComponent, TermsPageComponent]) {
        it(`offers the private support bot and a focusable page heading on ${page.name}`, () => {
            const fixture = TestBed.createComponent(page);
            fixture.detectChanges();
            const root = fixture.nativeElement as HTMLElement;
            expect(root.querySelector('h1')?.getAttribute('tabindex')).toBe('-1');
            expect(root.querySelector('app-support-contact a')?.getAttribute('href')).toBe('https://t.me/MnemaSupportBot');
            expect(root.textContent).not.toContain('свяжитесь с нами через наш репозиторий');
        });
    }
});

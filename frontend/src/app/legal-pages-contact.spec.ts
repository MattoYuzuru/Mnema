import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PrivacyPageComponent } from './privacy-page.component';
import { TermsPageComponent } from './terms-page.component';
import { SUPPORT_CONTACT, telegramContact } from './shared/support-contact';

describe('Public legal-page contact', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: SUPPORT_CONTACT, useValue: telegramContact('MnemaSupportBot') }] });
    });

    const pages: Type<unknown>[] = [PrivacyPageComponent, TermsPageComponent];
    for (const page of pages) {
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

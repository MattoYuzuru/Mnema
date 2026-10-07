import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PublicFooterComponent } from './public-footer.component';
import { SUPPORT_CONTACT, telegramContact } from './support-contact';

describe('PublicFooterComponent', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: SUPPORT_CONTACT, useValue: telegramContact('MnemaSupportBot') }] });
    });

    it('groups project, product, legal and support destinations with the owner-supplied operator details', () => {
        const fixture = TestBed.createComponent(PublicFooterComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect([...root.querySelectorAll('h2')].map(heading => heading.textContent))
            .toEqual(['О проекте', 'Разделы', 'Правовая информация', 'Контакты']);
        expect([...root.querySelectorAll('a')].map(link => link.getAttribute('href')))
            .toEqual(['/', '/#materials', '/#exercises', '/plans', '/events', '/privacy', '/terms', '/ai', 'https://t.me/MnemaSupportBot']);
        expect(root.textContent).toContain('ИП Рябушкин Матвей Игоревич');
        expect([...root.querySelectorAll('dl dd')].map(detail => detail.textContent)).toEqual(['771573834080', '326774600705952']);
        expect(root.textContent).not.toContain('Калмыков');
        for (const group of root.querySelectorAll('section, nav')) {
            expect(group.getAttribute('aria-labelledby')).toBe(group.querySelector('h2')?.id);
        }
    });

    it('gives two instances distinct navigation names, including the styleguide specimen', () => {
        const roots = [TestBed.createComponent(PublicFooterComponent), TestBed.createComponent(PublicFooterComponent)];
        roots.forEach(fixture => fixture.detectChanges());
        const ids = roots.flatMap(fixture => [...(fixture.nativeElement as HTMLElement).querySelectorAll('[id]')].map(element => element.id));
        expect(new Set(ids).size).toBe(ids.length);
    });
});

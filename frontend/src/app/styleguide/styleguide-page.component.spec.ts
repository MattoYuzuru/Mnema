import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ToastService } from '../core/notifications/toast.service';
import { GLYPH_NAMES } from '../shared/glyph.component';
import { ShareLinkService } from '../shared/share-link.service';
import { StyleguidePageComponent } from './styleguide-page.component';
import { SECTION_GROUPS } from './styleguide.data';

describe('StyleguidePageComponent', () => {
    let fixture: ComponentFixture<StyleguidePageComponent>;
    let root: HTMLElement;

    beforeEach(async () => {
        await TestBed.configureTestingModule({ providers: [provideRouter([])] }).compileComponents();
        fixture = TestBed.createComponent(StyleguidePageComponent);
        root = fixture.nativeElement as HTMLElement;
        await fixture.whenStable();
        fixture.detectChanges();
    });

    it('has one h1 and a section with a heading for every entry of the sidebar', () => {
        expect(root.querySelectorAll('h1:not([role=presentation])')).toHaveLength(1);
        const ids = SECTION_GROUPS.flatMap(group => group.sections.map(section => section.id));
        expect(ids).toHaveLength(new Set(ids).size);
        for (const id of ids) {
            const section = root.querySelector<HTMLElement>(`section#${id}`);
            expect(section, id).not.toBeNull();
            expect(section!.querySelector('h2'), id).not.toBeNull();
            expect(section!.getAttribute('aria-labelledby'), id).toBe(section!.querySelector('h2')!.id);
        }
    });

    it('links every section from the sidebar by fragment', () => {
        const links = [...root.querySelectorAll<HTMLAnchorElement>('nav[aria-label="Разделы стайлгайда"] a')];
        expect(links).toHaveLength(SECTION_GROUPS.flatMap(group => group.sections).length);
        for (const link of links) expect(link.getAttribute('href')).toMatch(/#[a-z]+$/u);
    });

    it('gives every specimen a «когда использовать» note', () => {
        const specimens = root.querySelectorAll('.sg-specimen');
        expect(specimens.length).toBeGreaterThan(20);
        specimens.forEach(specimen => expect(specimen.querySelector('.sg-when')?.textContent).toContain('Когда использовать'));
    });

    it('renders the real app components and classes', () => {
        for (const selector of [
            'app-hold-to-delete-button', 'app-segmented-choice', 'app-toggletip', 'app-usage-meter', 'app-mnema-select', 'app-choice-list',
            'app-batch-pager', 'app-author-chip', 'app-plan-option', 'app-promo-redeem .field-row', 'table.data-table', 'app-new-badge', 'app-native-document-renderer', 'app-telegram-glyph', 'app-mail-glyph', 'app-glyph[data-glyph=colonnade]', 'app-action-menu [role=menu][popover]', 'app-share-button button', 'app-public-deck-card article', 'app-support-contact', 'app-mail-contact button', 'app-legal-operator-block', 'app-sg-legal .toc', 'app-public-footer', 'button.generate-cta', 'button.button.primary',
            '.check-field > .check-row', '.settings-row.is-switch', '.settings-row.is-switch.is-live', 'fieldset.check-group > legend', '.cta-bar.cta-bar--inline', '.notice.error', '.empty-state', '.stamp', '.paper-surface.ruled', '.field-error'
        ]) {
            expect(root.querySelector(selector), selector).not.toBeNull();
        }
    });

    it('shows the live consent switch: fields follow it, and the pending state keeps controls focusable', () => {
        const stage = [...root.querySelectorAll<HTMLElement>('.sg-stage')].find(item => item.querySelector('.is-live'))!;
        const live = stage.querySelector<HTMLInputElement>('input[role=switch]')!;
        const fields = [...stage.querySelectorAll<HTMLInputElement>('fieldset.check-group input')].slice(0, 3);
        expect(live.checked).toBe(false);
        expect(fields.every(field => field.disabled && !field.checked)).toBe(true);
        live.checked = true;
        live.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        expect(fields.every(field => !field.disabled)).toBe(true);
        const pending = [...stage.querySelectorAll<HTMLInputElement>('input[aria-disabled=true]')];
        expect(pending.length).toBeGreaterThanOrEqual(2);
        expect(pending.every(field => !field.disabled)).toBe(true);
        expect(stage.querySelector('[role=status]')?.textContent).toBe('Сохраняем…');
    });

    it('shows the author chip with a photo, with the placeholder, truncated, and hidden', () => {
        const chips = [...root.querySelectorAll<HTMLElement>('#status app-author-chip')];
        expect(chips).toHaveLength(4);
        expect(chips[0].querySelector('img')?.getAttribute('width')).toBe('20');
        expect(chips[0].querySelector('.login')?.textContent).toBe('@anna.k');
        expect(chips[1].querySelector('img')).toBeNull();
        expect(chips[1].querySelector('.placeholder')?.textContent).toBe('A');
        expect(chips[2].querySelector('.login')?.textContent?.length).toBeGreaterThan(40);
        expect(chips[3].querySelector('.chip')).toBeNull();
    });

    it('catalogues every glyph of app-glyph and the card with its three states', () => {
        const names = [...root.querySelectorAll<HTMLElement>('#icons app-glyph')].map(glyph => glyph.dataset['glyph']);
        expect(names.sort()).toEqual([...GLYPH_NAMES].sort());
        const cards = [...root.querySelectorAll<HTMLElement>('#cards app-public-deck-card')];
        expect(cards).toHaveLength(2);
        expect(cards[0].querySelector('.audience')).not.toBeNull();
        expect(cards[1].querySelector('.audience')).toBeNull();
        expect(cards[1].querySelector('.chip')).toBeNull();
    });

    it('says what the menu specimen did, and shows the link field when copying failed', async () => {
        const links = TestBed.inject(ShareLinkService);
        const share = vi.spyOn(links, 'share').mockResolvedValue('failed');
        const specimen = [...root.querySelectorAll<HTMLElement>('#menus .sg-stage')].find(stage => stage.querySelector('app-action-menu'))!;
        const rows = [...specimen.querySelectorAll<HTMLButtonElement>('[role=menuitem]')];
        rows.find(row => row.textContent?.includes('Поделиться'))!.click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(share).toHaveBeenCalledWith('https://mnema.app/styleguide', 'Каталог Mnema (образец)');
        expect(specimen.querySelector('[role=status]')?.textContent).toBe('Скопировать не удалось.');
        expect(specimen.querySelector('app-share-link-field input')).not.toBeNull();
        rows.find(row => row.textContent?.includes('Пожаловаться'))!.click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(specimen.querySelector('[role=status]')?.textContent).toBe('Выбран пункт «report».');
        expect(specimen.querySelector('app-share-link-field')).toBeNull();
    });

    it('explains each share outcome in the menu specimen', async () => {
        const links = TestBed.inject(ShareLinkService);
        const specimen = [...root.querySelectorAll<HTMLElement>('#menus .sg-stage')].find(stage => stage.querySelector('app-action-menu'))!;
        const row = [...specimen.querySelectorAll<HTMLButtonElement>('[role=menuitem]')].find(item => item.textContent?.includes('Поделиться'))!;
        for (const [outcome, text] of [['shared', 'Системное меню приняло ссылку.'], ['copied', 'Ссылка скопирована.'], ['cancelled', 'Меню «Поделиться» закрыто без выбора.']] as const) {
            vi.spyOn(links, 'share').mockResolvedValue(outcome);
            row.click();
            await fixture.whenStable();
            fixture.detectChanges();
            expect(specimen.querySelector('[role=status]')?.textContent, outcome).toBe(text);
        }
    });

    it('reads the palette from the live tokens and grades the contrast pairs', async () => {
        const style = document.documentElement.style;
        for (const [token, value] of Object.entries({ '--mn-ink': '#281378', '--mn-sheet': '#fbf8ef', '--mn-body': '#342e44', '--mn-paper': '#f4f0e5' })) {
            style.setProperty(token, value);
        }
        try {
            const live = TestBed.createComponent(StyleguidePageComponent);
            await live.whenStable();
            live.detectChanges();
            const text = (live.nativeElement as HTMLElement).querySelector('#palette')!.textContent!;
            expect(text).toContain('#281378');
            const ratios = [...(live.nativeElement as HTMLElement).querySelectorAll('#semantic tbody tr')].map(row => row.textContent!);
            expect(ratios.find(row => row.includes('Заголовок и действие'))).toMatch(/13\.\d\d:1 · AAA/u);
        } finally {
            for (const token of ['--mn-ink', '--mn-sheet', '--mn-body', '--mn-paper']) style.removeProperty(token);
        }
    });

    it('previews reduced motion with the sidebar switch', () => {
        const rootElement = root.querySelector<HTMLElement>('.sg-root')!;
        expect(rootElement.hasAttribute('data-calm')).toBe(false);
        const toggle = root.querySelector<HTMLInputElement>('.sg-controls-panel input[type=checkbox]')!;
        toggle.checked = true;
        toggle.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        expect(rootElement.hasAttribute('data-calm')).toBe(true);
        expect(root.querySelector('#motion .notice')?.textContent).toContain('Спокойное движение включено');
    });

    it('shows toasts through the real ToastService', () => {
        const toasts = TestBed.inject(ToastService);
        const echo = vi.spyOn(toasts, 'echo');
        const notify = vi.spyOn(toasts, 'notify');
        const buttons = [...root.querySelectorAll<HTMLButtonElement>('#feedback .sg-stage .actions button')];
        buttons.forEach(button => button.click());
        expect(echo).toHaveBeenCalledOnce();
        expect(notify.mock.calls.map(call => call[2])).toEqual(['INFO', 'WARNING', 'ERROR']);
    });

    it('answers the promo field of the catalogue without a server and opens the promo window', async () => {
        const field = root.querySelector<HTMLInputElement>('app-promo-redeem input')!;
        field.value = 'nonsense';
        field.dispatchEvent(new Event('input'));
        root.querySelector<HTMLFormElement>('app-promo-redeem form')!.dispatchEvent(new Event('submit'));
        await fixture.whenStable();
        fixture.detectChanges();
        expect(root.querySelector('app-promo-redeem .field-error')?.textContent).toContain('не подходит');

        const open = [...root.querySelectorAll<HTMLButtonElement>('#menus button.button')].find(button => button.textContent?.includes('промо-окно'))!;
        open.click();
        fixture.detectChanges();
        expect(root.querySelector('app-promo-popup dialog')).not.toBeNull();
        root.querySelector<HTMLButtonElement>('app-promo-popup .promo-popup-close')!.click();
        fixture.detectChanges();
        expect(root.querySelector('app-promo-popup')).toBeNull();
    });

    it('opens and closes the «Попросить Мнему» window from its specimen', async () => {
        const open = [...root.querySelectorAll<HTMLButtonElement>('#menus button.button')].find(button => button.textContent?.includes('Открыть окно'))!;
        open.click();
        fixture.detectChanges();
        expect(root.querySelector('app-ai-prompt-window')).not.toBeNull();
        root.querySelector<HTMLButtonElement>('app-ai-prompt-window .window-close')!.click();
        fixture.detectChanges();
        expect(root.querySelector('app-ai-prompt-window')).toBeNull();
    });
});

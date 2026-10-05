import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ToastService } from '../core/notifications/toast.service';
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
            'app-batch-pager', 'app-plan-option', 'table.data-table', 'app-new-badge', 'app-native-document-renderer', 'button.generate-cta', 'button.button.primary',
            '.notice.error', '.empty-state', '.stamp', '.paper-surface.ruled', '.field-error'
        ]) {
            expect(root.querySelector(selector), selector).not.toBeNull();
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

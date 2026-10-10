import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { installPopoverShim, removePopoverShim } from '../../testing/popover-shim';
import { PublicDeckCard } from './public-deck-card';
import { PublicDeckCardComponent } from './public-deck-card.component';
import { ShareLinkService, ShareOutcome } from './share-link.service';

const FULL: PublicDeckCard = {
    id: 'full', title: 'Испанские глаголы', description: 'Спряжение и примеры.', topic: 'Языки', language: 'Испанский',
    author: { username: 'anna.k', avatarSrc: null }, materialCount: 312, exerciseCount: 640, media: ['audio', 'image'],
    addedCount: 1234, learningNowCount: 87, updatedAt: '2026-10-03T09:00:00Z', shareUrl: 'https://mnema.app/d/AbCdEfGh12/x'
};

@Component({
    imports: [PublicDeckCardComponent],
    template: `<app-public-deck-card [deck]="deck" [link]="link" [headingLevel]="level" [menuItems]="items" (action)="actions.push($event)" />`
})
class HostComponent {
    deck = FULL;
    link: string | null = null;
    level: 2 | 3 | 4 = 3;
    items = [{ id: 'share', label: 'Поделиться' }, { id: 'report', label: 'Пожаловаться' }];
    readonly actions: string[] = [];
}

function audience(root: HTMLElement): string[][] {
    return [...root.querySelectorAll('.audience > div')].map(row => [row.querySelector('dt')!.textContent!, row.querySelector('dd [aria-hidden]')!.textContent!]);
}

describe('PublicDeckCardComponent', () => {
    let share: ReturnType<typeof vi.fn<(url: string, title: string) => Promise<ShareOutcome>>>;

    function create(patch: Partial<HostComponent> = {}): { root: HTMLElement; fixture: ReturnType<typeof TestBed.createComponent<HostComponent>> } {
        share = vi.fn<(url: string, title: string) => Promise<ShareOutcome>>().mockResolvedValue('copied');
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: ShareLinkService, useValue: { share } }] });
        const fixture = TestBed.createComponent(HostComponent);
        Object.assign(fixture.componentInstance, patch);
        fixture.detectChanges();
        return { root: fixture.nativeElement as HTMLElement, fixture };
    }

    beforeEach(() => installPopoverShim());
    afterEach(() => removePopoverShim());

    it('shows every part of a full card, with no stars or likes', () => {
        const { root } = create();
        const text = root.textContent!.replace(/\s+/gu, ' ');
        expect(root.querySelector('h3')?.textContent?.trim()).toBe('Испанские глаголы');
        expect(root.querySelector('.description')?.textContent).toBe('Спряжение и примеры.');
        expect([...root.querySelectorAll('.tags .stamp')].map(tag => tag.textContent)).toEqual(['Тема: Языки', 'Язык: Испанский']);
        expect([...root.querySelectorAll('.tags .visually-hidden')].map(item => item.textContent)).toEqual(['Тема: ', 'Язык: ']);
        expect(root.querySelector('.media')?.getAttribute('role')).toBe('list');
        expect(root.querySelector('app-author-chip .login')?.textContent).toBe('@anna.k');
        expect(text).toContain('312 материалов · 640 упражнений');
        expect([...root.querySelectorAll('.media li')].map(item => item.textContent?.trim())).toEqual(['аудио', 'картинки']);
        expect(audience(root)).toEqual([['Добавили', '1,2\u00a0тыс.'], ['Учат сейчас', '87']]);
        expect([...root.querySelectorAll('.audience dd .visually-hidden')].map(item => item.textContent)).toEqual(['1,2 тысячи', '87']);
        expect(root.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-03T09:00:00Z');
        expect(root.querySelector('time')?.textContent).toMatch(/^Обновлена 3 октября/u);
        expect(text).not.toMatch(/★|♥|лайк|звезд/iu);
        expect(root.querySelector('article')?.getAttribute('aria-labelledby')).toBe(root.querySelector('h3')?.id);
    });

    it('gives every card its own heading id, whatever the deck, and labels the article by it', () => {
        const first = create();
        const second = TestBed.createComponent(HostComponent);
        second.detectChanges();
        const ids = [first.root, second.nativeElement as HTMLElement].map(root => root.querySelector('h3')!.id);
        expect(ids[0]).not.toBe(ids[1]);
        expect(ids[0]).not.toContain('full');
        expect(first.root.querySelector('article')?.getAttribute('aria-labelledby')).toBe(ids[0]);
    });

    it('takes the heading level from the screen: h3 by default, h2 or h4 on request', () => {
        expect(create().root.querySelector('h3.title')).not.toBeNull();
        for (const level of [2, 4] as const) {
            TestBed.resetTestingModule();
            const { root } = create({ level });
            expect(root.querySelector(`h${level}.title`)?.textContent?.trim(), String(level)).toBe('Испанские глаголы');
            expect(root.querySelectorAll('h2.title, h3.title, h4.title')).toHaveLength(1);
        }
    });

    it('names the menu button after the deck and offers the given rows', () => {
        const { root } = create();
        expect(root.querySelector('button.trigger')?.getAttribute('aria-label')).toBe('Действия с колодой «Испанские глаголы»');
        expect([...root.querySelectorAll('[role=menuitem]')].map(item => item.textContent?.trim())).toEqual(['Поделиться', 'Пожаловаться']);
    });

    it('renders the title as a link only when it has a route', () => {
        expect(create().root.querySelector('h3 a')).toBeNull();
        TestBed.resetTestingModule();
        const { root } = create({ link: '/d/AbCdEfGh12/x' });
        expect(root.querySelector('h3 a')?.getAttribute('href')).toBe('/d/AbCdEfGh12/x');
    });

    it('renders without an author, without media, and with small audiences', () => {
        const { root } = create({ deck: { ...FULL, author: { username: null, avatarSrc: null }, media: [], addedCount: 9, learningNowCount: 0 } });
        expect(root.querySelector('app-author-chip .chip')).toBeNull();
        expect(root.querySelector('.media')).toBeNull();
        expect(root.querySelector('.audience')).toBeNull();
        expect(root.textContent).not.toContain('Добавили');
        expect(root.textContent).not.toContain('Учат сейчас');
        expect(root.querySelector('h3')).not.toBeNull();
    });

    it('shows only the audience count that reaches the threshold', () => {
        const { root } = create({ deck: { ...FULL, addedCount: 12, learningNowCount: 9 } });
        expect(audience(root)).toEqual([['Добавили', '12']]);
    });

    it('drops the update line for an unreadable date', () => {
        expect(create({ deck: { ...FULL, updatedAt: 'x' } }).root.querySelector('time')).toBeNull();
    });

    it('shares the card itself on «Поделиться» and reports the other rows', async () => {
        const { root, fixture } = create();
        const [shareRow, reportRow] = [...root.querySelectorAll<HTMLButtonElement>('[role=menuitem]')];
        shareRow.click();
        await fixture.whenStable();
        expect(share).toHaveBeenCalledWith(FULL.shareUrl, FULL.title);
        expect(fixture.componentInstance.actions).toEqual([]);
        reportRow.click();
        expect(fixture.componentInstance.actions).toEqual(['report']);
    });

    it('shows the selectable link under the card when copying failed', async () => {
        const { root, fixture } = create();
        share.mockResolvedValue('failed');
        root.querySelector<HTMLButtonElement>('[role=menuitem]')!.click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(root.querySelector<HTMLInputElement>('app-share-link-field input')?.value).toBe(FULL.shareUrl);
    });
});

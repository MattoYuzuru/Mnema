import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import forks from '../../../../contracts/decks/forks.json';
import type { DeckFork } from '../features/own-decks/fork/fork.models';
import { CopyMarkComponent } from './copy-mark.component';
import { CopyOriginComponent } from './copy-origin.component';
import { CopyStatusComponent } from './copy-status.component';

const states = forks.fork as unknown as Record<'preparing' | 'failed' | 'ready' | 'sourceClosed' | 'detached', DeckFork>;

describe('CopyMarkComponent', () => {
    function mark(fork: DeckFork): HTMLElement {
        const fixture = TestBed.createComponent(CopyMarkComponent);
        fixture.componentRef.setInput('fork', fork);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }
    /** What a screen reader reads: the text without what is hidden from it. */
    const words = (root: HTMLElement) => {
        const copy = root.cloneNode(true) as HTMLElement;
        copy.querySelectorAll('[aria-hidden=true]').forEach(hidden => hidden.remove());
        return copy.textContent!.replace(/\s+/gu, ' ').trim();
    };

    it('says «Копия» with the shared copy glyph, which assistive technology skips', () => {
        const root = mark(states.ready);
        expect(words(root)).toBe('Копия');
        expect(root.querySelector('app-glyph')!.getAttribute('data-glyph')).toBe('copy-branch');
        expect(root.querySelector('app-glyph')!.getAttribute('aria-hidden')).toBe('true');
        expect(root.querySelector('.state')).toBeNull();
    });

    it('adds the one state that matters, in words, and a pause for the reader between them', () => {
        expect(words(mark(states.preparing))).toBe('Копия: Готовим колоду…');
        expect(words(mark(states.failed))).toBe('Копия: Не удалось подготовить колоду');
        expect(words(mark(states.sourceClosed))).toBe('Копия: Источник больше недоступен');
        expect(mark(states.preparing).querySelector('.sep')!.getAttribute('aria-hidden')).toBe('true');
    });

    it('says nothing more of a detached copy, even when its source was closed', () => {
        expect(words(mark(states.detached))).toBe('Копия');
        expect(words(mark({ ...states.sourceClosed, detached: true }))).toBe('Копия');
    });
});

describe('CopyOriginComponent', () => {
    beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

    function origin(fork: DeckFork): HTMLElement {
        const fixture = TestBed.createComponent(CopyOriginComponent);
        fixture.componentRef.setInput('fork', fork);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }
    const words = (root: HTMLElement) => root.textContent!.replace(/\s+/gu, ' ').trim();

    it('names the source and links its title to the public view while the author deck is readable', () => {
        const root = origin(states.ready);
        expect(words(root)).toBe('Копия колоды «Испанский язык: базовые глаголы»');
        const link = root.querySelector<HTMLAnchorElement>('a')!;
        expect(link.textContent).toBe(states.ready.upstreamTitle);
        expect(link.getAttribute('href')).toBe(`/d/${states.ready.upstreamCode}`);
        expect(root.querySelector('.stamp')).toBeNull();
    });

    it('says the approved sentence, and draws no link, once the author closed the source', () => {
        const root = origin(states.sourceClosed);
        expect(words(root)).toBe(forks.texts.sourceClosed);
        expect(root.querySelector('a')).toBeNull();
    });

    it('adds the stamp «Отвязана от автора» after the owner detached the copy', () => {
        expect(origin(states.detached).querySelector('.stamp')!.textContent).toBe('Отвязана от автора');
        expect(words(origin({ ...states.sourceClosed, detached: true }))).toContain(forks.texts.sourceClosed);
    });
});

describe('CopyStatusComponent', () => {
    function status(inputs: Record<string, unknown>): { root: HTMLElement; retry: ReturnType<typeof vi.fn>; check: ReturnType<typeof vi.fn> } {
        const fixture = TestBed.createComponent(CopyStatusComponent);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        const retry = vi.fn();
        const check = vi.fn();
        fixture.componentInstance.retry.subscribe(retry);
        fixture.componentInstance.check.subscribe(check);
        fixture.detectChanges();
        return { root: fixture.nativeElement as HTMLElement, retry, check };
    }

    it('says «Готовим колоду…» with what will be in the copy, and offers no action while it works', () => {
        const { root } = status({ state: 'preparing', summary: '40 материалов · 12 упражнений' });
        const section = root.querySelector('section')!;
        expect(section.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')!.id);
        // Busy is said by the buttons that are busy, never by the block: a polling page is not a busy region.
        expect(section.hasAttribute('aria-busy')).toBe(false);
        expect(root.querySelector('h2')!.textContent).toBe('Готовим колоду…');
        expect(root.querySelector('.line')!.textContent).toBe('В копии будет: 40 материалов · 12 упражнений.');
        expect(root.querySelector('.hint')!.textContent).toBe('Страница обновится сама, когда колода будет готова.');
        expect(root.querySelector('button')).toBeNull();
        expect(root.querySelector('[role=status], [role=alert], [aria-live]')).toBeNull();
        expect(root.querySelector('progress, [role=progressbar]')).toBeNull();
    });

    it('leaves the counts out when the server named none', () => {
        expect(status({ state: 'preparing' }).root.querySelector('.line')).toBeNull();
    });

    it('says what failed and offers «Повторить», busy while the request is on its way', () => {
        const idle = status({ state: 'failed' });
        expect(idle.root.querySelector('section')!.classList.contains('error')).toBe(true);
        expect(idle.root.querySelector('section')!.getAttribute('aria-busy')).toBeNull();
        expect(idle.root.querySelector('h2')!.textContent).toBe('Не удалось подготовить колоду');
        expect(idle.root.querySelector('.hint')!.textContent).toBe('Заполнить копию не получилось. Повторите подготовку или удалите колоду.');
        const retry = idle.root.querySelector<HTMLButtonElement>('button')!;
        expect(retry.textContent).toBe('Повторить');
        retry.click();
        expect(idle.retry).toHaveBeenCalledTimes(1);

        const busy = status({ state: 'failed', busy: true }).root.querySelector<HTMLButtonElement>('button')!;
        expect(busy.textContent).toBe('Повторяем…');
        expect(busy.getAttribute('aria-busy')).toBe('true');
        expect(busy.getAttribute('aria-disabled')).toBe('true');
        expect(busy.disabled).toBe(false);
    });

    it('offers «Проверить снова» once the page stopped asking by itself, and says so', () => {
        const stalled = status({ state: 'preparing', stalled: true });
        expect(stalled.root.querySelector('section')!.getAttribute('aria-busy')).toBeNull();
        expect(stalled.root.querySelector('.hint')!.textContent).toBe('Подготовка идёт дольше обычного. Страница больше не обновляется сама.');
        const check = stalled.root.querySelector<HTMLButtonElement>('button')!;
        expect(check.textContent).toBe('Проверить снова');
        check.click();
        expect(stalled.check).toHaveBeenCalledTimes(1);
    });

    it('draws the heading at the level the page asks for: 2 by default, 3 or 4 inside a deeper page', () => {
        expect(status({ state: 'preparing' }).root.querySelector('h2')).not.toBeNull();
        for (const level of [3, 4]) {
            const { root } = status({ state: 'failed', headingLevel: level });
            expect(root.querySelector(`h${level}`)!.textContent).toBe('Не удалось подготовить колоду');
            expect(root.querySelector('section')!.getAttribute('aria-labelledby')).toBe(root.querySelector(`h${level}`)!.id);
            expect(root.querySelector('h2')).toBeNull();
        }
    });

    it('gives every block its own heading id', () => {
        const ids = [status({ state: 'preparing' }), status({ state: 'failed' })].map(item => item.root.querySelector('h2')!.id);
        expect(new Set(ids).size).toBe(2);
    });
});

import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { SEARCH_IDEAS } from './search-ideas';
import { TYPEWRITER_TIMING, TypewriterHintComponent } from './typewriter-hint.component';

@Component({
    imports: [TypewriterHintComponent],
    template: `<div class="field"><input aria-label="Поиск" /><app-typewriter-hint [suppressed]="suppressed()" [ideas]="ideas()" [seed]="seed()" [still]="still()" /></div>`
})
class HostComponent {
    readonly suppressed = signal(false);
    readonly ideas = signal<readonly string[]>(['abc']);
    readonly seed = signal('test');
    readonly still = signal(false);
}

const { startMs, typeMs, eraseMs, holdMs, gapMs } = TYPEWRITER_TIMING;

describe('TypewriterHintComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let host: HostComponent;
    let root: HTMLElement;

    const create = (): void => {
        fixture = TestBed.createComponent(HostComponent);
        host = fixture.componentInstance;
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
    };
    const text = (): string => root.querySelector('.idea')?.textContent ?? '';
    const advance = (ms: number): void => { vi.advanceTimersByTime(ms); fixture.detectChanges(); };
    const media = (matches: boolean): void => {
        vi.spyOn(document.defaultView!, 'matchMedia').mockReturnValue({ matches, addEventListener: vi.fn(), removeEventListener: vi.fn() } as unknown as MediaQueryList);
    };

    beforeEach(() => { vi.useFakeTimers(); });
    afterEach(() => { vi.useRealTimers(); });

    it('is a decorative layer: hidden from assistive technology, never a target of the pointer, never writing into the field', () => {
        create();
        const hint = root.querySelector('app-typewriter-hint')!;
        expect(hint.getAttribute('aria-hidden')).toBe('true');
        expect(hint.querySelector('input, button, a, [tabindex]')).toBeNull();
        expect(root.querySelector('input')!.value).toBe('');
        advance(startMs + typeMs * 3);
        expect(text()).toBe('abc');
        expect(root.querySelector('input')!.value).toBe('');
        expect(root.querySelector('input')!.hasAttribute('placeholder')).toBe(false);
    });

    it('types an idea letter by letter, holds it, erases it and takes the next', () => {
        create();
        host.ideas.set(['ab', 'cd']);
        fixture.detectChanges();
        expect(text()).toBe('');
        advance(startMs);
        const first = text();
        const second = first === 'a' ? 'cd' : 'ab';
        expect(['a', 'c']).toContain(first);
        advance(typeMs);
        expect(text()).toBe(first === 'a' ? 'ab' : 'cd');
        advance(holdMs - 1);
        expect(text()).toBe(first === 'a' ? 'ab' : 'cd');
        advance(1);
        expect(text()).toBe(first);
        advance(eraseMs);
        expect(text()).toBe('');
        advance(gapMs);
        expect(text()).toBe(second.slice(0, 1));
        advance(typeMs);
        expect(text()).toBe(second);
    });

    it('rests on its idea instead of looping when there is only one', () => {
        create();
        advance(startMs + typeMs * 2);
        expect(text()).toBe('abc');
        advance(120_000);
        expect(text()).toBe('abc');
    });

    it('plays one pass through at most three ideas and then rests on the last one typed (WCAG 2.2.2)', () => {
        create();
        host.ideas.set(['aa', 'bb', 'cc', 'dd', 'ee']);
        fixture.detectChanges();
        const complete = new Set<string>();
        for (let elapsed = 0; elapsed < 120_000; elapsed += 10) {
            advance(10);
            if (text().length === 2) complete.add(text());
        }
        expect(complete.size).toBe(3);
        const last = text();
        expect(last).toHaveLength(2);
        advance(120_000);
        expect(text()).toBe(last);
    });

    it('stays at rest after the pass, also when the field is focused and left again', () => {
        create();
        advance(startMs + typeMs * 2);
        host.suppressed.set(true);
        fixture.detectChanges();
        host.suppressed.set(false);
        fixture.detectChanges();
        advance(120_000);
        expect(text()).toBe('abc');
    });

    it('gives one order for one seed and takes its ideas from the pool by default', () => {
        const firstIdeas: string[] = [];
        for (const seed of ['one', 'one', 'two']) {
            TestBed.resetTestingModule();
            create();
            host.ideas.set(SEARCH_IDEAS);
            host.seed.set(seed);
            fixture.detectChanges();
            advance(startMs + typeMs * 60);
            firstIdeas.push(text());
            expect(SEARCH_IDEAS.some(idea => idea.startsWith(text()))).toBe(true);
        }
        expect(firstIdeas[0]).toBe(firstIdeas[1]);
    });

    it('hides and stops all timers while the field has focus or a value, and starts over afterwards', () => {
        create();
        advance(startMs + typeMs);
        expect(text()).toBe('ab');
        host.suppressed.set(true);
        fixture.detectChanges();
        expect(root.querySelector('.idea')).toBeNull();
        expect(vi.getTimerCount()).toBe(0);
        advance(60_000);
        expect(root.querySelector('.idea')).toBeNull();
        host.suppressed.set(false);
        fixture.detectChanges();
        expect(text()).toBe('');
        advance(startMs);
        expect(text()).toBe('a');
    });

    it('stops while the browser tab is hidden and resumes when it is shown', () => {
        const state = vi.spyOn(Document.prototype, 'visibilityState', 'get').mockReturnValue('visible');
        create();
        advance(startMs);
        expect(text()).toBe('a');
        state.mockReturnValue('hidden');
        document.dispatchEvent(new Event('visibilitychange'));
        fixture.detectChanges();
        expect(vi.getTimerCount()).toBe(0);
        state.mockReturnValue('visible');
        document.dispatchEvent(new Event('visibilitychange'));
        fixture.detectChanges();
        expect(vi.getTimerCount()).toBe(1);
        advance(startMs);
        expect(text()).toBe('a');
    });

    it('with reduced motion shows one static idea and starts no timer', () => {
        media(true);
        create();
        expect(vi.getTimerCount()).toBe(0);
        expect(text()).toBe('abc');
        advance(60_000);
        expect(text()).toBe('abc');
        host.suppressed.set(true);
        fixture.detectChanges();
        expect(root.querySelector('.idea')).toBeNull();
    });

    it('is static on request, whatever the system says', () => {
        create();
        host.ideas.set(['abc', 'de']);
        host.still.set(true);
        fixture.detectChanges();
        const idea = text();
        expect(['abc', 'de']).toContain(idea);
        advance(60_000);
        expect(text()).toBe(idea);
        host.still.set(false);
        fixture.detectChanges();
        expect(text()).toBe('');
        advance(startMs);
        expect(text()).toHaveLength(1);
    });

    it('clears its timer when destroyed', () => {
        create();
        advance(startMs);
        expect(vi.getTimerCount()).toBe(1);
        fixture.destroy();
        expect(vi.getTimerCount()).toBe(0);
    });
});

interface NodeFs { readFileSync(path: string, encoding: 'utf8'): string }

describe('typewriter hint stylesheet contract', () => {
    it('draws the idea in GrayText in forced colours, as placeholder text is', async () => {
        const { readFileSync } = await import('node:fs' as string) as NodeFs;
        const root = (globalThis as unknown as { process: { cwd(): string } }).process.cwd();
        const source = readFileSync(`${root}/src/app/shared/typewriter-hint.component.ts`, 'utf8');
        expect(source).toMatch(/@media \(forced-colors: active\) \{ \.idea \{ color: GrayText; \} \}/);
    });
});

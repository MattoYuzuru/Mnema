import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AutoLoadComponent } from './auto-load.component';

describe('AutoLoadComponent', () => {
    let fixture: ComponentFixture<AutoLoadComponent>;
    let requested = vi.fn<() => void>();
    let observations: { callback: IntersectionObserverCallback; options?: IntersectionObserverInit; disconnect: ReturnType<typeof vi.fn> }[];
    const originalObserver = window.IntersectionObserver;
    const originalResize = window.ResizeObserver;
    let resized: ResizeObserverCallback;

    beforeEach(async () => {
        observations = [];
        window.IntersectionObserver = class {
            constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
                observations.push({ callback, options, disconnect: this.disconnect });
            }
            observe = vi.fn();
            disconnect = vi.fn();
        } as unknown as typeof IntersectionObserver;
        window.ResizeObserver = class {
            constructor(callback: ResizeObserverCallback) { resized = callback; }
            observe = vi.fn();
            disconnect = vi.fn();
        } as unknown as typeof ResizeObserver;
        await TestBed.configureTestingModule({ imports: [AutoLoadComponent] }).compileComponents();
        fixture = TestBed.createComponent(AutoLoadComponent);
        fixture.componentRef.setInput('content', document.createElement('ul'));
        fixture.componentRef.setInput('continuation', 'next-page');
        requested = vi.fn<() => void>();
        fixture.componentInstance.loadNext.subscribe(requested);
        await render();
    });

    afterEach(() => { window.IntersectionObserver = originalObserver; window.ResizeObserver = originalResize; });

    async function render(): Promise<void> { fixture.detectChanges(); await fixture.whenStable(); }
    function cross(index = observations.length - 1, visible = true): void {
        observations[index].callback([{ isIntersecting: visible } as IntersectionObserverEntry], {} as IntersectionObserver);
    }

    it('emits only when the tail enters the preload region, once per armed cursor, without a load button', () => {
        cross(undefined, false);
        expect(requested).not.toHaveBeenCalled();
        cross(); cross();
        expect(requested).toHaveBeenCalledTimes(1);
        expect(fixture.nativeElement.querySelector('button')).toBeNull();
    });

    it('disconnects during loading and rejects a queued old callback; the next cursor re-arms it', async () => {
        cross();
        const old = observations.length - 1;
        fixture.componentRef.setInput('loading', true); await render();
        expect(observations[old].disconnect).toHaveBeenCalled();
        cross(old);
        expect(requested).toHaveBeenCalledTimes(1);
        expect(fixture.nativeElement.querySelector('[role=status]')?.textContent).toContain('Загружаем');
        fixture.componentRef.setInput('continuation', 'page-three');
        fixture.componentRef.setInput('loading', false); await render();
        cross();
        expect(requested).toHaveBeenCalledTimes(2);
    });

    it('keeps an error stopped until an explicit retry and retains keyboard focus', async () => {
        cross();
        fixture.componentRef.setInput('error', 'Нет связи'); await render();
        cross();
        expect(requested).toHaveBeenCalledTimes(1);
        const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
        document.body.append(fixture.nativeElement); button.focus(); button.click();
        expect(requested).toHaveBeenCalledTimes(2);
        expect(document.activeElement).toBe(button);
        fixture.nativeElement.remove();
    });

    it('never requests after the end or while the panel is closed', async () => {
        fixture.componentRef.setInput('continuation', null); await render(); cross();
        fixture.componentRef.setInput('continuation', 'next');
        fixture.componentRef.setInput('enabled', false); await render(); cross();
        expect(requested).not.toHaveBeenCalled();
        fixture.componentRef.setInput('enabled', true); await render(); cross();
        expect(requested).toHaveBeenCalledTimes(1);
    });

    it('does not loop when a response repeats its continuation, but re-arms for an explicit new snapshot', async () => {
        cross();
        fixture.componentRef.setInput('loading', true); await render();
        fixture.componentRef.setInput('loading', false); await render(); cross();
        expect(requested).toHaveBeenCalledTimes(1);
        fixture.componentRef.setInput('context', 1); await render(); cross();
        expect(requested).toHaveBeenCalledTimes(2);
    });

    it('observes the supplied scrolling panel and ignores a disconnected observer after reflow', async () => {
        const root = document.createElement('div');
        fixture.componentRef.setInput('root', root); await render();
        expect(observations.at(-1)?.options?.root).toBe(root);
        const old = observations.length - 1;
        resized([], {} as ResizeObserver);
        cross(old);
        expect(requested).not.toHaveBeenCalled();
        cross();
        expect(requested).toHaveBeenCalledTimes(1);
    });

    it('rejects callbacks from the previous content/cursor and from a destroyed view', async () => {
        const old = observations.length - 1;
        fixture.componentRef.setInput('content', document.createElement('ol'));
        fixture.componentRef.setInput('continuation', 'other-deck'); await render(); cross(old);
        expect(requested).not.toHaveBeenCalled();
        fixture.destroy(); cross();
        expect(requested).not.toHaveBeenCalled();
    });

    it('tears down cleanly when optional ResizeObserver or IntersectionObserver is absent', async () => {
        window.ResizeObserver = undefined as unknown as typeof ResizeObserver;
        fixture.componentRef.setInput('continuation', 'without-resize'); await render(); cross();
        expect(requested).toHaveBeenCalledTimes(1);
        window.IntersectionObserver = undefined as unknown as typeof IntersectionObserver;
        fixture.componentRef.setInput('continuation', 'without-intersection'); await render();
        window.dispatchEvent(new Event('resize'));
        expect(requested).toHaveBeenCalledTimes(1);
    });
});

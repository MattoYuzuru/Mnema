import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { spyObj } from '../../../testing/mocks';
import { ToastService } from '../notifications/toast.service';
import { ActionOptions, JourneyAction } from './onboarding.models';
import { TourAnchorDirective } from './tour-anchor.directive';
import { TOUR_PERSISTENCE, TourEngine } from './tour-engine.service';
import { TourEvents } from './tour-events';
import { TourHostComponent } from './tour-host.component';
import { paragraphs, slides } from './tour-rich-text';
import { TourDefinition, TourStep } from './tour.models';

const step = (overrides: Partial<TourStep> & Pick<TourStep, 'key' | 'anchor'>): TourStep =>
    ({ title: `Заголовок ${overrides.key}`, body: paragraphs('Текст <span class="key">шага</span>.'), gate: 'next', ...overrides });

const TOUR: TourDefinition = {
    id: 'sample',
    branch: 'SAMPLE',
    steps: [
        step({ key: 'sample.hub', anchor: 'hub.header' }),
        step({ key: 'sample.material', anchor: 'hub.material', body: slides('Один.', 'Два.') }),
        step({ key: 'sample.study', anchor: 'hub.start', gate: { action: 'study.started', label: 'Начать занятие' } })
    ]
};

@Component({
    imports: [TourHostComponent, TourAnchorDirective],
    template: `
      <header id="masthead"><a href="/decks">Мои колоды</a></header>
      <main id="main">
        <section id="header" mnTourAnchor="hub.header"><h1>Колода</h1></section>
        <section id="material" mnTourAnchor="hub.material"><p>Материал</p></section>
        <aside id="aside"><button type="button" id="other">Другое</button></aside>
        <button type="button" id="start" mnTourAnchor="hub.start">Начать занятие</button>
      </main>
      <app-tour-host />`
})
class PageComponent {}

describe('TourHostComponent', () => {
    let fixture: ComponentFixture<PageComponent>;
    let engine: TourEngine;
    let events: TourEvents;
    let calls: [JourneyAction, ActionOptions | undefined][];
    let toasts: { echo: ReturnType<typeof vi.fn> };
    let root: HTMLElement;

    beforeEach(async () => {
        calls = [];
        toasts = { echo: vi.fn() };
        TestBed.configureTestingModule({
            imports: [PageComponent],
            providers: [provideRouter([{ path: '**', children: [] }]), { provide: ToastService, useValue: spyObj<ToastService>(toasts) },
                { provide: TOUR_PERSISTENCE, useValue: { act: (action: JourneyAction, options?: ActionOptions) => { calls.push([action, options]); return Promise.resolve(null); } } }]
        });
        fixture = TestBed.createComponent(PageComponent);
        root = fixture.nativeElement;
        engine = TestBed.inject(TourEngine);
        events = TestBed.inject(TourEvents);
        fixture.detectChanges();
        await fixture.whenStable();
    });

    const settle = async (): Promise<void> => {
        TestBed.tick();
        await fixture.whenStable();
        TestBed.tick();
    };
    const card = (): HTMLDialogElement | null => root.querySelector('app-tour-host dialog');
    const heading = (): HTMLElement => root.querySelector('app-tour-host h2')!;
    const live = (): string => root.querySelector('app-tour-host [aria-live="polite"]:not(.slider p)')?.textContent?.trim() ?? '';
    const liveRegion = (): HTMLElement => root.querySelector<HTMLElement>('app-tour-host > div.visually-hidden')!;
    const inert = (): string[] => [...root.querySelectorAll('[inert]')].map(element => element.id || element.tagName.toLowerCase()).sort();
    const press = (key: string, target: EventTarget = document.body): KeyboardEvent => {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
        target.dispatchEvent(event);
        return event;
    };

    describe('while no tour runs', () => {
        it('draws only its live region, empty', () => {
            expect(card()).toBeNull();
            expect(root.querySelector('app-tour-scrim')).toBeNull();
            expect(root.querySelector('app-tour-pause')).toBeNull();
            expect(liveRegion().getAttribute('aria-live')).toBe('polite');
            expect(liveRegion().textContent).toBe('');
        });

        it('ignores Escape', () => {
            expect(press('Escape').defaultPrevented).toBe(false);
            expect(calls).toEqual([]);
        });
    });

    describe('a step', () => {
        it('draws the veil and the card, announces «Шаг N из M» with the title, and puts the focus on the heading', async () => {
            engine.start(TOUR);
            await settle();
            expect(root.querySelector('app-tour-scrim')).not.toBeNull();
            expect(card()).not.toBeNull();
            expect(card()!.getAttribute('role')).toBe('dialog');
            expect(heading().textContent).toBe('Заголовок sample.hub');
            expect(liveRegion().textContent).toBe('Шаг 1 из 3. Заголовок sample.hub');
            expect(document.activeElement).toBe(heading());
        });

        it('announces the next step and moves the focus to its heading', async () => {
            engine.start(TOUR);
            await settle();
            (root.querySelector('.steps .button.primary') as HTMLButtonElement).click();
            await settle();
            expect(liveRegion().textContent).toBe('Шаг 2 из 3. Заголовок sample.material');
            expect(document.activeElement).toBe(heading());
            expect(heading().textContent).toBe('Заголовок sample.material');
        });

        it('cuts the veil out around the target once the anchor is there, and draws a whole veil before', async () => {
            engine.start(TOUR);
            await settle();
            expect(root.querySelectorAll('app-tour-scrim path')[0].getAttribute('fill-rule')).toBe('evenodd');
            expect(root.querySelector('app-tour-scrim .frame')).not.toBeNull();
        });

        it('opens an informational step modally', async () => {
            const modal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
            const modeless = vi.spyOn(HTMLDialogElement.prototype, 'show');
            engine.start(TOUR);
            await settle();
            expect(modal).toHaveBeenCalledTimes(1);
            expect(modeless).not.toHaveBeenCalled();
            expect(inert()).toEqual([]);
        });

        it('does nothing when the veil is clicked', async () => {
            engine.start(TOUR);
            await settle();
            const veil = root.querySelector('app-tour-scrim path')!;
            veil.dispatchEvent(new MouseEvent('click', { bubbles: true }));
            veil.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            await settle();
            expect(engine.active()).toBe(true);
            expect(engine.paused()).toBe(false);
            expect(engine.index()).toBe(0);
            expect(calls).toEqual([]);
        });
    });

    describe('a step that waits for an action', () => {
        const toGate = async (): Promise<void> => {
            engine.start(TOUR, 'sample.study');
            await settle();
        };

        it('opens non-modally, offers no «Дальше», and leaves only the target, the card and the toasts live', async () => {
            const modeless = vi.spyOn(HTMLDialogElement.prototype, 'show');
            const modal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
            await toGate();
            expect(modeless).toHaveBeenCalled();
            expect(modal).not.toHaveBeenCalled();
            expect(root.querySelector('.steps .button.primary')).toBeNull();
            expect(root.querySelector('.gate')!.textContent).toContain('Нажмите «Начать занятие», чтобы продолжить');
            // The target, the card's host and everything on the path to them stay live.
            expect(inert()).toEqual(['aside', 'header', 'masthead', 'material']);
            expect(root.querySelector('#start')!.closest('[inert]')).toBeNull();
            expect(card()!.closest('[inert]')).toBeNull();
        });

        it('moves on when the target component says so, and gives the page back', async () => {
            await toGate();
            events.emit('study.started');
            await settle();
            expect(engine.active()).toBe(false);
            expect(card()).toBeNull();
            expect(inert()).toEqual([]);
        });

        it('gives the page back when the tour is paused and takes it again on resume', async () => {
            await toGate();
            expect(inert().length).toBeGreaterThan(0);
            engine.pause();
            await settle();
            expect(inert()).toEqual([]);
            engine.resume();
            await settle();
            expect(inert()).toEqual(['aside', 'header', 'masthead', 'material']);
        });

        it('gives the page back when the learner goes back to an informational step', async () => {
            await toGate();
            engine.back();
            await settle();
            expect(inert()).toEqual([]);
        });

        it('lets the tour be finished or stopped without leaking inert', async () => {
            await toGate();
            engine.stop();
            await settle();
            expect(inert()).toEqual([]);
        });
    });

    describe('Escape', () => {
        it('folds the tour into the pause plaque, takes the focus there and records the pause once', async () => {
            engine.start(TOUR);
            await settle();
            expect(press('Escape', heading()).defaultPrevented).toBe(true);
            await settle();
            expect(engine.paused()).toBe(true);
            expect(card()).toBeNull();
            expect(root.querySelector('app-tour-scrim')).toBeNull();
            const plaque = root.querySelector('app-tour-pause')!;
            expect(plaque.querySelector('[role=region]')!.getAttribute('aria-label')).toBe('Знакомство на паузе');
            expect(document.activeElement).toBe(plaque.querySelector('button'));
            expect(liveRegion().textContent).toBe('');
            press('Escape');
            expect(calls).toEqual([['PAUSE', { stepKey: 'sample.hub' }]]);
        });

        it('treats the dialog\'s own cancel request as the same pause', async () => {
            engine.start(TOUR);
            await settle();
            const cancel = new Event('cancel', { cancelable: true });
            card()!.dispatchEvent(cancel);
            press('Escape');
            await settle();
            expect(cancel.defaultPrevented).toBe(true);
            expect(calls).toEqual([['PAUSE', { stepKey: 'sample.hub' }]]);
        });

        it('returns to the same step, with the focus on its heading, when the learner continues', async () => {
            engine.start(TOUR);
            await settle();
            engine.next();
            await settle();
            press('Escape');
            await settle();
            (root.querySelector('app-tour-pause button') as HTMLButtonElement).click();
            await settle();
            expect(root.querySelector('app-tour-pause')).toBeNull();
            expect(heading().textContent).toBe('Заголовок sample.material');
            expect(document.activeElement).toBe(heading());
            expect(liveRegion().textContent).toBe('Шаг 2 из 3. Заголовок sample.material');
            expect(calls.map(([action]) => action)).toEqual(['STEP', 'PAUSE', 'RESUME']);
        });

        it('first disarms an armed finish button and does not pause', async () => {
            engine.start(TOUR);
            await settle();
            const hold = root.querySelector('.hold-button') as HTMLButtonElement;
            hold.click();
            await settle();
            expect(hold.getAttribute('aria-pressed')).toBe('true');
            press('Escape', hold);
            await settle();
            expect(hold.getAttribute('aria-pressed')).toBe('false');
            expect(engine.paused()).toBe(false);
        });
    });

    describe('finishing', () => {
        it('finishes after the hold, removes everything and says so in a toast', async () => {
            vi.useFakeTimers();
            try {
                engine.start(TOUR);
                await settle();
                const hold = root.querySelector('.hold-button') as HTMLButtonElement;
                hold.click();
                hold.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 3, clientY: 3, bubbles: true }));
                await vi.advanceTimersByTimeAsync(2000);
                await settle();
                expect(engine.active()).toBe(false);
                expect(card()).toBeNull();
                expect(calls).toEqual([['COMPLETE', {}]]);
                expect(toasts.echo).toHaveBeenCalledWith('Знакомство завершено. Пройти его снова можно в профиле.');
            } finally {
                vi.useRealTimers();
            }
        });
    });

    describe('a step whose anchor is not on the screen', () => {
        it('shows the text variant after four seconds and lets the learner go on', async () => {
            vi.useFakeTimers();
            try {
                engine.start({ id: 'x', branch: 'SAMPLE', steps: [step({ key: 'sample.hub', anchor: 'nowhere' }), step({ key: 'sample.material', anchor: 'hub.material' })] });
                await settle();
                expect(root.querySelector('.tour-body')!.textContent).toContain('Текст шага.');
                expect(root.querySelector('app-tour-scrim .frame')).toBeNull();
                await vi.advanceTimersByTimeAsync(4_000);
                await settle();
                expect(root.querySelector('.tour-body')!.textContent!.trim()).toBe('Этого места сейчас нет на экране. Продолжим со следующего шага.');
                (root.querySelector('.steps .button.primary') as HTMLButtonElement).click();
                await settle();
                expect(engine.index()).toBe(1);
                expect(heading().textContent).toBe('Заголовок sample.material');
            } finally {
                vi.useRealTimers();
            }
        });

        it('says the page is being prepared while the app says so', async () => {
            engine.start({ id: 'x', branch: 'SAMPLE', steps: [step({ key: 'sample.hub', anchor: 'nowhere' })] });
            engine.setPreparing(true);
            await settle();
            expect(root.querySelector('.tour-body')!.textContent!.trim()).toBe('Готовим колоду…');
        });
    });

    describe('external steps and the study route', () => {
        const FINALE = { key: 'finale.ai' as const, anchor: '', title: '', body: paragraphs(''), gate: 'next' as const, external: true };

        it('shows nothing for a step another component draws, and does not announce it', async () => {
            engine.start({ id: 'f', branch: null, steps: [step({ key: 'finale.done', anchor: 'hub.header' }), FINALE] });
            await settle();
            expect(card()).not.toBeNull();
            engine.next();
            await settle();
            expect(card()).toBeNull();
            expect(root.querySelector('app-tour-scrim')).toBeNull();
            expect(liveRegion().textContent).toBe('');
            expect(engine.total()).toBe(1);
        });

        it('leaves the pause plaque out of a study session, and brings it back on any other page', async () => {
            engine.start(TOUR, 'sample.study');
            engine.pause();
            await settle();
            expect(root.querySelector('app-tour-pause')).not.toBeNull();
            await TestBed.inject(Router).navigateByUrl('/decks/1/study');
            await settle();
            expect(root.querySelector('app-tour-pause')).toBeNull();
            expect(engine.paused()).toBe(true);
            await TestBed.inject(Router).navigateByUrl('/decks/1');
            await settle();
            expect(root.querySelector('app-tour-pause')).not.toBeNull();
        });
    });

    describe('geometry (the numbers come from stubbed rectangles: jsdom has no layout)', () => {
        const rect = (left: number, top: number, width: number, height: number): DOMRect =>
            ({ left, top, width, height, right: left + width, bottom: top + height, x: left, y: top, toJSON: () => ({}) });
        const stubLayout = (target: DOMRect, cardSize = { width: 384, height: 200 }, viewportWidth = 1024): void => {
            Object.defineProperty(document.documentElement, 'clientWidth', { value: viewportWidth, configurable: true });
            vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
                if (this.id === 'header') return target;
                if (this.tagName === 'DIALOG') return rect(0, 0, cardSize.width, cardSize.height);
                return rect(0, 0, 0, 0);
            });
        };
        afterEach(() => { delete (document.documentElement as { clientWidth?: number }).clientWidth; });

        it('cuts the veil out 8px around the target and puts the card under it, 16px away', async () => {
            stubLayout(rect(100, 100, 200, 50));
            engine.start(TOUR);
            await settle();
            const frame = root.querySelector<HTMLElement>('app-tour-scrim .frame')!;
            expect([frame.style.left, frame.style.top, frame.style.width, frame.style.height]).toEqual(['92px', '92px', '216px', '66px']);
            expect(root.querySelector('app-tour-scrim path')!.getAttribute('d')).toContain('M94 92');
            expect(card()!.style.left).toBe('92px');
            expect(card()!.style.top).toBe('174px');
            expect(card()!.classList.contains('sheet')).toBe(false);
        });

        it('puts the card above the target when there is no room below, and aside when there is none on either side', async () => {
            stubLayout(rect(100, 500, 200, 50));
            engine.start(TOUR);
            await settle();
            // Bottom of the window is 768: 500 + 50 + 8 + 16 + 200 does not fit, so the card goes above (500 - 8 - 16 - 200).
            expect(card()!.style.top).toBe('276px');
        });

        it('keeps the cut-out with the target when the page scrolls or the window is resized', async () => {
            let top = 100;
            vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
                if (this.id === 'header') return rect(100, top, 200, 50);
                if (this.tagName === 'DIALOG') return rect(0, 0, 384, 200);
                return rect(0, 0, 0, 0);
            });
            Object.defineProperty(document.documentElement, 'clientWidth', { value: 1024, configurable: true });
            engine.start(TOUR);
            await settle();
            const frame = (): HTMLElement => root.querySelector<HTMLElement>('app-tour-scrim .frame')!;
            expect(frame().style.top).toBe('92px');
            top = 160;
            const frames: FrameRequestCallback[] = [];
            const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation(callback => { frames.push(callback); return frames.length; });
            document.dispatchEvent(new Event('scroll'));
            window.dispatchEvent(new Event('scroll'));
            document.body.dispatchEvent(new Event('scroll'));
            // Scroll does not bubble but is observed in the capture phase on the window: the dispatch on the window schedules one frame.
            expect(raf).toHaveBeenCalled();
            frames.forEach(callback => callback(0));
            await settle();
            expect(frame().style.top).toBe('152px');
        });

        it('scrolls a target that is above the window into view before it measures', async () => {
            const scrollBy = vi.spyOn(window, 'scrollBy');
            const scrollIntoView = vi.spyOn(HTMLElement.prototype, 'scrollIntoView');
            stubLayout(rect(100, -300, 200, 50));
            engine.start(TOUR);
            await settle();
            expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
            expect(scrollBy).toHaveBeenCalledWith({ top: -316, behavior: 'instant' });
        });

        it('is a bottom sheet on a phone, with the page given room under it and the target scrolled above it', async () => {
            const scrollBy = vi.spyOn(window, 'scrollBy');
            stubLayout(rect(20, 500, 200, 50), { width: 375, height: 300 }, 375);
            engine.start(TOUR);
            await settle();
            expect(card()!.classList.contains('sheet')).toBe(true);
            expect(card()!.style.left).toBe('');
            expect(card()!.style.top).toBe('');
            expect(document.body.style.paddingBlockEnd).toBe('300px');
            // The free band ends at 768 - 300 - 16 = 452; the target's bottom edge is at 550.
            expect(scrollBy).toHaveBeenCalledWith({ top: 98, behavior: 'instant' });
            engine.stop();
            await settle();
            expect(document.body.style.paddingBlockEnd).toBe('');
        });

        it('puts the card in the middle of the window while the target is not there', async () => {
            stubLayout(rect(0, 0, 0, 0), { width: 384, height: 200 });
            engine.start({ id: 'x', branch: 'SAMPLE', steps: [step({ key: 'sample.hub', anchor: 'nowhere' })] });
            await settle();
            expect(card()!.style.left).toBe('320px');
            expect(card()!.style.top).toBe('284px');
            expect(root.querySelector('app-tour-scrim .frame')).toBeNull();
        });
    });

    describe('housekeeping', () => {
        it('puts nothing of the page back where it was not: no inert and no padding left after the tour', async () => {
            document.body.style.paddingBlockEnd = '5px';
            engine.start(TOUR);
            await settle();
            engine.stop();
            await settle();
            expect(document.body.style.paddingBlockEnd).toBe('5px');
            expect(inert()).toEqual([]);
            document.body.style.paddingBlockEnd = '';
        });

        it('lets go of its listeners and the inert marks when the page is destroyed in the middle of a step', async () => {
            engine.start(TOUR, 'sample.study');
            await settle();
            fixture.destroy();
            expect(document.querySelectorAll('[inert]')).toHaveLength(0);
            engine.stop();
        });
    });
});

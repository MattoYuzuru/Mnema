import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { spyObj } from '../../../testing/mocks';
import { AuthService } from '../../auth.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { HINT_ANCHOR_WAIT_MS, PathHint } from '../../core/onboarding/path-hint.service';
import { TourAnchors } from '../../core/onboarding/tour-anchors';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { PathHintComponent } from './path-hint.component';

const REQUEST = { title: 'Сохраните заметку «На потом»', text: 'Сохраните сюда мысль или ссылку — разберёте её потом.', anchor: 'hub.capture' };

describe('PathHintComponent (the in-place hint, no veil)', () => {
    let fixture: ComponentFixture<PathHintComponent>;
    let root: HTMLElement;
    let hint: PathHint;
    let anchors: TourAnchors;
    let anchor: HTMLAnchorElement;
    let heading: HTMLElement;

    const settle = async (ms = 0): Promise<void> => {
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(ms);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    };
    const card = (): HTMLElement | null => root.querySelector('app-path-hint-card');
    const startHint = async (): Promise<void> => {
        hint.begin(REQUEST);
        hint.arm();
        await settle();
    };

    beforeEach(async () => {
        vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: TourEngine, useValue: spyObj<TourEngine>({ active: signal(false), paused: signal(false) }) },
                { provide: JourneyStore, useValue: { journey: signal(null), view: signal(null), error: signal(null) } },
                { provide: AuthService, useValue: { status: signal('authenticated') } }]
        });
        await TestBed.inject(Router).navigateByUrl('/decks/d1');
        hint = TestBed.inject(PathHint);
        anchors = TestBed.inject(TourAnchors);
        heading = document.createElement('h1');
        heading.tabIndex = -1;
        const main = document.createElement('main');
        main.id = 'main-content';
        anchor = document.createElement('a');
        anchor.href = '/decks/d1/capture';
        anchor.textContent = 'На потом';
        main.append(heading, anchor);
        document.body.append(main);
        anchors.register('hub.capture', anchor);
        fixture = TestBed.createComponent(PathHintComponent);
        root = fixture.nativeElement as HTMLElement;
        document.body.append(root);
        fixture.detectChanges();
    });
    afterEach(() => {
        vi.useRealTimers();
        document.body.replaceChildren();
    });

    it('draws nothing until a hint is asked for and its page has been reached', async () => {
        expect(card()).toBeNull();
        hint.begin(REQUEST);
        await settle();
        expect(card()).toBeNull();
    });

    it('stands next to the anchor with the milestone title and the sentence, and takes the focus on its heading', async () => {
        await startHint();
        expect(card()).not.toBeNull();
        expect(card()!.classList.contains('floating')).toBe(true);
        expect(card()!.querySelector('h2')?.textContent).toBe(REQUEST.title);
        expect(card()!.querySelector('p')?.textContent).toBe(REQUEST.text);
        expect(document.activeElement).toBe(card()!.querySelector('h2'));
        expect(hint.awaitingFocus()).toBe(false);
        // No veil, and the page behind stays live.
        expect(root.querySelector('app-tour-scrim, dialog')).toBeNull();
        expect(anchor.closest('[inert]')).toBeNull();
    });

    it('«Понятно» closes it and returns the focus to the anchor', async () => {
        await startHint();
        (card()!.querySelector('button') as HTMLButtonElement).click();
        await settle();
        expect(card()).toBeNull();
        expect(document.activeElement).toBe(anchor);
        expect(hint.active()).toBe(false);
    });

    it('Escape closes it and returns the focus to the anchor', async () => {
        await startHint();
        const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        card()!.querySelector('h2')!.dispatchEvent(escape);
        await settle();
        expect(escape.defaultPrevented).toBe(true);
        expect(card()).toBeNull();
        expect(document.activeElement).toBe(anchor);
    });

    it('leaves Escape alone when something else has handled it or another dialog is open', async () => {
        await startHint();
        const handled = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        card()!.querySelector('h2')!.addEventListener('keydown', event => event.preventDefault(), { once: true });
        card()!.querySelector('h2')!.dispatchEvent(handled);
        await settle();
        expect(card()).not.toBeNull();
        const dialog = document.createElement('dialog');
        dialog.setAttribute('open', '');
        document.body.append(dialog);
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
        await settle();
        expect(card()).not.toBeNull();
    });

    it('closes when the learner does the action on the anchor, and does not take the focus from what the action does', async () => {
        await startHint();
        const other = document.createElement('button');
        document.body.append(other);
        other.focus();
        anchor.addEventListener('click', event => event.preventDefault());
        anchor.click();
        await settle();
        expect(card()).toBeNull();
        expect(document.activeElement).toBe(other);
    });

    it('returns the focus to the first control inside an anchor that is not itself one (a form, a list)', async () => {
        anchors.register('hub.capture', document.createElement('div'));
        const region = document.createElement('section');
        const control = document.createElement('input');
        region.append(control);
        document.body.append(region);
        anchors.register('hub.capture', region);
        await startHint();
        (card()!.querySelector('button') as HTMLButtonElement).click();
        await settle();
        expect(document.activeElement).toBe(control);
    });

    it('falls back to the page heading when the anchor holds nothing to focus', async () => {
        const plain = document.createElement('div');
        document.body.append(plain);
        anchors.register('hub.capture', plain);
        await startHint();
        (card()!.querySelector('button') as HTMLButtonElement).click();
        await settle();
        expect(document.activeElement).toBe(heading);
    });

    describe('the anchor does not appear', () => {
        beforeEach(() => anchors.register('hub.capture', anchor)());

        it('says the same sentence in the flow at the top of the page after four seconds, with the focus on it', async () => {
            await startHint();
            expect(card()).toBeNull();
            await settle(HINT_ANCHOR_WAIT_MS);
            expect(card()).not.toBeNull();
            expect(card()!.classList.contains('floating')).toBe(false);
            expect(card()!.querySelector('h2')?.textContent).toBe(REQUEST.title);
            expect(card()!.querySelector('p')?.textContent).toBe(REQUEST.text);
            expect(document.activeElement).toBe(card()!.querySelector('h2'));
        });

        it('closes with «Понятно» and gives the focus to the page heading (there is no anchor to return to)', async () => {
            await startHint();
            await settle(HINT_ANCHOR_WAIT_MS);
            (card()!.querySelector('button') as HTMLButtonElement).click();
            await settle();
            expect(card()).toBeNull();
            expect(document.activeElement).toBe(heading);
        });
    });

    it('goes away with the page it belongs to', async () => {
        await startHint();
        await TestBed.inject(Router).navigateByUrl('/community');
        await settle();
        expect(card()).toBeNull();
    });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { PlansApiService } from '../plans/plans-api.service';
import { parsePlans } from '../plans/plans-api.service';
import { plansBody } from '../plans/plans-test-data';
import { FinaleComponent } from './finale.component';
import { OnboardingFlow } from './onboarding-flow.service';
import { PATH_TARGET } from './onboarding-targets';
import { Setup, setup } from './onboarding-test-data';

const plain = (text: string | null): string => (text ?? '').replace(/\u00a0/gu, ' ').trim();

describe('FinaleComponent', () => {
    let fixture: ComponentFixture<FinaleComponent>;
    let root: HTMLElement;
    let s: Setup;
    let engine: TourEngine;
    let plans: { load: ReturnType<typeof vi.fn> };

    const render = async (ai: boolean, plansResult: 'ok' | 'error' = 'ok'): Promise<void> => {
        plans = { load: vi.fn(() => plansResult === 'ok' ? of(parsePlans(plansBody())) : throwError(() => new Error('down'))) };
        s = setup({ ai, journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false }, providers: [{ provide: PlansApiService, useValue: plans }] });
        engine = s.engine;
        fixture = TestBed.createComponent(FinaleComponent);
        root = fixture.nativeElement;
        await s.flow.startTour('OWN');
        await settle();
    };
    const settle = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); TestBed.tick(); fixture.detectChanges(); };
    const toFinale = async (): Promise<void> => {
        for (const [event, payload] of [['deck.created', { deckId: 'd1' }], ['material.published', { deckId: 'd1', memberKey: 'm' }], ['exercise.published', { deckId: 'd1' }]] as const) s.events.emit(event, payload);
        s.events.emit('study.started');
        s.events.emit('study.completed');
        await settle();
    };

    it('draws nothing while the tour is on an ordinary step', async () => {
        await render(true);
        expect(root.querySelector('dialog')).toBeNull();
    });

    describe('«ИИ в Mnema»', () => {
        beforeEach(async () => { await render(true); await toFinale(); });

        it('is a modal card on the veil, named by its heading, with the focus on it', () => {
            const card = root.querySelector('dialog')!;
            expect(card.open).toBe(true);
            expect(card.getAttribute('aria-labelledby')).toBe('finale-ai-title');
            expect(document.activeElement).toBe(root.querySelector('#finale-ai-title'));
            expect(root.querySelector('app-tour-scrim')).not.toBeNull();
        });

        it('says what the approved text says: the thesis, the Workshop and the checking with their key words', () => {
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('ИИ в Mnema');
            expect(plain(root.querySelector('h2')!.textContent)).toBe('Найти материал легко. Выучить — труднее');
            expect(plain(root.textContent)).toContain('Трудно не найти материал, а упорядочить его и действительно выучить.');
            expect([...root.querySelectorAll('li .key')].map(item => item.textContent)).toEqual(['Мастерская', 'Проверка ИИ']);
        });

        it('fills the free allowance from the plans and links «Тарифы» without going there by itself', async () => {
            await vi.waitFor(() => expect(root.querySelector('.hint')).not.toBeNull());
            await settle();
            const hint = plain(root.querySelector('.hint')!.textContent);
            expect(hint).toContain('Бесплатно каждый месяц: ');
            expect(hint).not.toMatch(/в месяц ·|в месяц\./u);
            expect(hint).toContain('Тарифы');
            expect(root.querySelector('a')!.getAttribute('href')).toBe('/plans');
            expect(plans.load).toHaveBeenCalledTimes(1);
            expect(TestBed.inject(Router).url).not.toBe('/plans');
        });

        it('pauses the tour when «Тарифы» is followed, then goes to the plans', async () => {
            await vi.waitFor(() => expect(root.querySelector('a')).not.toBeNull());
            await settle();
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
            root.querySelector<HTMLAnchorElement>('a')!.click();
            expect(engine.paused()).toBe(true);
            expect(engine.step()?.key).toBe('finale.ai');
            expect(navigate).toHaveBeenCalledWith('/plans');
            await settle();
            expect(root.querySelector('dialog')).toBeNull();
        });

        it('goes to the congratulation with «Дальше»', async () => {
            [...root.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent === 'Дальше')!.click();
            await settle();
            expect(engine.step()?.key).toBe('finale.done');
            expect(root.querySelector('#finale-done-title')).not.toBeNull();
        });

        it('does not close on Escape by itself: the tour decides (it pauses)', () => {
            const cancel = new Event('cancel', { cancelable: true });
            root.querySelector('dialog')!.dispatchEvent(cancel);
            expect(cancel.defaultPrevented).toBe(true);
        });
    });

    describe('without the plans', () => {
        it('leaves the allowance line out and still lets the learner go on', async () => {
            await render(true, 'error');
            await toFinale();
            await vi.waitFor(() => expect(plans.load).toHaveBeenCalled());
            await settle();
            expect(root.querySelector('.hint')).toBeNull();
            expect(root.querySelector('button')!.textContent).toBe('Дальше');
        });
    });

    describe('without AI', () => {
        it('has no «ИИ в Mnema»: the session ends in the congratulation', async () => {
            await render(false);
            await toFinale();
            expect(engine.step()?.key).toBe('finale.done');
            expect(root.querySelector('#finale-ai-title')).toBeNull();
            expect(plans.load).not.toHaveBeenCalled();
            expect(root.querySelector('#finale-done-title')).not.toBeNull();
        });
    });

    describe('the congratulation', () => {
        beforeEach(async () => { await render(false); await toFinale(); });

        it('is the night with its stars in a modal dialog, with the focus on its heading, and says the approved words', () => {
            const night = root.querySelector('dialog')!;
            expect(night.open).toBe(true);
            expect(night.getAttribute('aria-labelledby')).toBe('finale-done-title');
            expect(root.querySelectorAll('app-night-sky .star').length).toBeGreaterThan(0);
            expect(document.activeElement).toBe(root.querySelector('#finale-done-title'));
            expect(plain(root.querySelector('h1')!.textContent)).toBe('Начало положено');
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Знакомство завершено');
        });

        it('links «Пути» to the one target constant, in the sentence the owner approved', () => {
            const link = root.querySelector<HTMLAnchorElement>('.lead a')!;
            expect(link.textContent).toBe('«Пути»');
            expect(link.getAttribute('href')).toBe(PATH_TARGET);
            expect(plain(root.querySelector('.lead')!.textContent)).toBe('Это ваш обычный путь в Mnema: колода, материалы, занятие. Все возможности собраны в «Пути» в профиле.');
        });

        it('has no confetti or motion of its own: static stars unless the night twinkles', () => {
            expect(root.querySelector('canvas, video, img')).toBeNull();
        });

        it('completes the journey and returns to the deck the learner worked on', async () => {
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            [...root.querySelectorAll<HTMLButtonElement>('button')].find(item => plain(item.textContent) === 'К колоде')!.click();
            expect(s.store.calls.at(-1)).toEqual(['COMPLETE', {}]);
            expect(engine.lastEnd()?.reason).toBe('completed');
            expect(navigate).toHaveBeenCalledWith(['/decks', 'd1']);
            await settle();
            expect(root.querySelector('dialog')).toBeNull();
        });

        it('goes to the list of decks when there is no deck to go back to', async () => {
            TestBed.inject(OnboardingFlow).reset();
            await s.flow.startTour('OWN', 'own.study');
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            engine.pause();
            engine.resumeAt('finale.done');
            await settle();
            [...root.querySelectorAll<HTMLButtonElement>('button')].find(item => plain(item.textContent) === 'К колоде')!.click();
            expect(navigate).toHaveBeenCalledWith(['/decks']);
        });
    });

    it('keeps the veil as large as the window', async () => {
        await render(true);
        await toFinale();
        window.dispatchEvent(new Event('resize'));
        await settle();
        const svg = root.querySelector('app-tour-scrim svg')!;
        expect(svg.getAttribute('width')).toBe(String(window.innerWidth));
    });
});

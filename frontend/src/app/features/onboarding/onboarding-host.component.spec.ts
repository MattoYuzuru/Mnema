import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AuthService, AuthStatus } from '../../auth.service';
import { QuietZone } from '../../core/notifications/quiet-zone';
import { AttentionCoordinator } from '../../core/onboarding/attention-coordinator';
import { TourHints } from '../../core/onboarding/tour-hints';
import { OnboardingHostComponent } from './onboarding-host.component';
import { Setup, setup } from './onboarding-test-data';

describe('OnboardingHostComponent', () => {
    let fixture: ComponentFixture<OnboardingHostComponent>;
    let root: HTMLElement;
    let s: Setup;
    const status = signal<AuthStatus>('authenticated');
    const user = signal<{ accountId: string } | null>({ accountId: 'a' });

    const render = async (options: Parameters<typeof setup>[0] = {}, before: (s: Setup) => Promise<void> | void = () => undefined): Promise<void> => {
        s = setup({ ...options, providers: [{ provide: AuthService, useValue: { status, user } }, ...(options.providers ?? [])] });
        await before(s);
        fixture = TestBed.createComponent(OnboardingHostComponent);
        root = fixture.nativeElement;
        await settle();
    };
    const settle = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); TestBed.tick(); fixture.detectChanges(); };

    beforeEach(() => { status.set('authenticated'); user.set({ accountId: 'a' }); });
    afterEach(() => { vi.useRealTimers(); document.querySelectorAll('.other-modal').forEach(element => element.remove()); });

    describe('the journey of the account', () => {
        it('is read once for a signed-in account', async () => {
            await render();
            expect(s.store.load).toHaveBeenCalledTimes(1);
        });

        it('is not read for a guest, and signing out forgets it', async () => {
            status.set('anonymous');
            await render();
            expect(s.store.load).not.toHaveBeenCalled();
            expect(s.store.reset).toHaveBeenCalled();
        });
    });

    describe('opening the first entry', () => {
        it('opens the welcome by itself for an account that was offered it, and tells the coordinator', async () => {
            await render();
            expect(root.querySelector('dialog.first-entry')!.hasAttribute('open')).toBe(true);
            expect(TestBed.inject(AttentionCoordinator).visible()).toContain('first-entry');
        });

        it('does not open for a guest', async () => {
            status.set('anonymous');
            await render();
            expect(root.querySelector('dialog')).toBeNull();
        });

        it('does not open for the holdout, who keep the old inline question', async () => {
            await render({ journey: { autoOffer: false } });
            expect(root.querySelector('dialog')).toBeNull();
        });

        it('does not open while onboarding is off', async () => {
            await render({ journey: null });
            expect(root.querySelector('dialog')).toBeNull();
        });

        it('waits while a study task is open and comes up afterwards', async () => {
            const quiet = new QuietZone();
            quiet.set(true);
            await render({ journey: { state: 'IN_PROGRESS', stepKey: 'survey.days', autoOffer: false }, providers: [{ provide: QuietZone, useValue: quiet }] });
            expect(root.querySelector('dialog')).toBeNull();
            quiet.set(false);
            await settle();
            expect(root.querySelector('dialog')).not.toBeNull();
        });

        it('does not open over a tour, nor over another modal dialog; it looks again a second later', async () => {
            vi.useFakeTimers();
            const other = document.createElement('dialog');
            other.className = 'other-modal';
            document.body.append(other);
            other.setAttribute('open', '');
            await render();
            expect(root.querySelector('dialog')).toBeNull();
            other.removeAttribute('open');
            await vi.advanceTimersByTimeAsync(1_000);
            await settle();
            expect(root.querySelector('dialog')).not.toBeNull();
        });

        it('keeps looking while the other modal stays, and asks everything again each time: a journey that was settled meanwhile is not opened', async () => {
            vi.useFakeTimers();
            const other = document.createElement('dialog');
            other.className = 'other-modal';
            document.body.append(other);
            other.setAttribute('open', '');
            await render();
            await vi.advanceTimersByTimeAsync(2_500);
            expect(root.querySelector('dialog.first-entry')).toBeNull();
            // The offer was answered elsewhere while the modal was open: the next look finds nothing to open.
            s.store.view.set({ ...s.store.view()!, journey: { ...s.store.view()!.journey!, state: 'SKIPPED' } });
            other.removeAttribute('open');
            await vi.advanceTimersByTimeAsync(1_000);
            await settle();
            expect(s.flow.card()).toBeNull();
        });

        it('does not open over a tour that came up while it waited for another modal', async () => {
            vi.useFakeTimers();
            const other = document.createElement('dialog');
            other.className = 'other-modal';
            document.body.append(other);
            other.setAttribute('open', '');
            await render();
            TestBed.inject(AttentionCoordinator).report('system-error', true);
            other.removeAttribute('open');
            await vi.advanceTimersByTimeAsync(1_000);
            await settle();
            expect(s.flow.card()).toBeNull();
            TestBed.inject(AttentionCoordinator).report('system-error', false);
            await settle();
            expect(s.flow.card()).toBe('welcome');
        });

        it('does not open while a tour is on the screen', async () => {
            await render({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } }, async ready => { await ready.flow.startTour('COMMUNITY'); });
            expect(s.engine.active()).toBe(true);
            expect(root.querySelector('dialog.first-entry')).toBeNull();
        });

        it('reopens at the card a reload interrupted', async () => {
            await render({ journey: { state: 'IN_PROGRESS', stepKey: 'survey.days', autoOffer: false } });
            expect(s.flow.card()).toBe('survey.days');
            expect(root.querySelector('h2')!.textContent).toBe('Сколько дней в неделю хотите заниматься?');
        });

        it('hands the focus to the page heading when the surface goes away from under it', async () => {
            const main = document.createElement('main');
            main.id = 'main-content';
            main.innerHTML = '<h1 tabindex="-1">Мои колоды</h1>';
            document.body.append(main);
            try {
                await render();
                await s.flow.skipAll();
                await settle();
                await new Promise(resolve => setTimeout(resolve, 0));
                expect(document.activeElement).toBe(main.querySelector('h1'));
            } finally { main.remove(); }
        });

        it('takes the surface away when onboarding is switched off, and tells the coordinator it is gone', async () => {
            await render();
            expect(s.flow.card()).toBe('welcome');
            s.store.view.set({ ...s.store.view()!, enabled: false, journey: null });
            await settle();
            expect(s.flow.card()).toBeNull();
            expect(TestBed.inject(AttentionCoordinator).visible()).not.toContain('first-entry');
        });
    });

    describe('onboarding switched off while the page is open', () => {
        it('takes a tour in progress down with it, and not before the first answer', async () => {
            await render({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } }, async ready => { await ready.flow.startTour('COMMUNITY'); });
            expect(s.engine.active()).toBe(true);
            s.store.view.set({ ...s.store.view()!, enabled: false, journey: null });
            await settle();
            expect(s.engine.active()).toBe(false);
        });
    });

    describe('a tour of another origin', () => {
        it('is not stopped when onboarding is switched off: only the tours that record into the journey are', async () => {
            await render({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false } });
            const demonstration = { act: vi.fn(() => Promise.resolve({ status: 'failed' as const })) };
            s.engine.start({ id: 'demo', branch: null, steps: [{ key: 'finale.ai', anchor: 'hub.start', title: 'Т', body: { kind: 'paragraphs', paragraphs: [] }, gate: 'next' }] },
                undefined, { persistence: demonstration });
            s.store.view.set({ ...s.store.view()!, enabled: false, journey: null });
            await settle();
            expect(s.engine.active()).toBe(true);
        });
    });

    describe('a journey paused somewhere else', () => {
        it('shows the plaque once the page has the journey, and does not jump into the tour', async () => {
            await render({ journey: { state: 'PAUSED', stepKey: 'own.material', branch: 'OWN', autoOffer: false }, ai: true });
            expect(s.engine.active()).toBe(true);
            expect(s.engine.paused()).toBe(true);
            expect(s.flow.card()).toBeNull();
        });
    });

    describe('the offer to make one\'s own deck', () => {
        const stuck = async (): Promise<void> => {
            await render({ journey: { state: 'IN_PROGRESS', stepKey: 'community.search', branch: 'COMMUNITY', autoOffer: false } });
            // A reload finds the journey in progress and folds it into the plaque (paused on the server); the learner continues it.
            s.engine.stop();
            await s.store.act('RESUME');
            await s.flow.startTour('COMMUNITY');
            await settle();
            TestBed.inject(TourHints).stuck.set(true);
            await settle();
        };

        it('appears after two minutes of browsing, with the approved words and focus on its heading', async () => {
            await stuck();
            const nudge = root.querySelector('app-tour-nudge')!;
            expect(nudge.querySelector('h2')!.textContent).toBe('Не нашли подходящую?');
            expect(nudge.textContent).toContain('Создайте свою колоду — это займёт пару минут.');
            await new Promise(resolve => setTimeout(resolve, 0));
            expect(document.activeElement).toBe(nudge.querySelector('h2'));
        });

        it('moves the tour to «Своя колода» with «Создать свою»', async () => {
            await stuck();
            [...root.querySelectorAll<HTMLButtonElement>('app-tour-nudge button')].find(item => item.textContent === 'Создать свою')!.click();
            await new Promise(resolve => setTimeout(resolve, 0));
            await settle();
            expect(s.engine.definition()?.id).toBe('own-v1');
            expect(root.querySelector('app-tour-nudge')).toBeNull();
        });

        it('leaves the community tour where it is when the server refuses the move', async () => {
            await stuck();
            await s.store.act('PAUSE');
            await s.flow.switchToOwn();
            expect(s.store.rejections.at(-1)).toEqual({ action: 'STEP', reason: 'transition' });
            expect(s.engine.definition()?.id).toBe('community-v1');
        });

        it('goes away with «Назад» and leaves the tour where it is', async () => {
            await stuck();
            [...root.querySelectorAll<HTMLButtonElement>('app-tour-nudge button')].find(item => item.textContent === 'Назад')!.click();
            await settle();
            expect(root.querySelector('app-tour-nudge')).toBeNull();
            expect(s.engine.definition()?.id).toBe('community-v1');
        });

        it('is not drawn while the tour is paused or over', async () => {
            await stuck();
            s.engine.pause();
            await settle();
            expect(root.querySelector('app-tour-nudge')).toBeNull();
        });
    });
});

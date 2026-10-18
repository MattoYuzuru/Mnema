import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';

import journey from '../../../../../contracts/onboarding/journey.json';
import { AuthService } from '../../auth.service';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { Journey, OnboardingView } from '../../core/onboarding/onboarding.models';
import { PathNavigation } from '../../core/onboarding/path-navigation.service';
import { PATH_HIDDEN_STORAGE_PREFIX, PATH_NOTICE_STORAGE_PREFIX, PathPreferences } from '../../core/onboarding/path-preferences';
import { ReplayTourComponent } from './replay-tour.component';
import { FakeJourneyStore, viewOf } from './onboarding-test-data';
import { OnboardingFlow } from './onboarding-flow.service';
import { PathPageComponent, neverOffered } from './path-page.component';

const midway = journey.views.pathMidway as OnboardingView;
const fresh = journey.views.offered as OnboardingView;

describe('PathPageComponent («Путь Mnema»)', () => {
    let store: FakeJourneyStore;
    let go: ReturnType<typeof vi.fn>;
    let router: Router;

    const render = async (view: OnboardingView | null = midway, options: { error?: 'load' | null; journey?: Partial<OnboardingView['journey'] & object> } = {}) => {
        store = new FakeJourneyStore({}, {});
        store.view.set(view === null ? null : options.journey === undefined ? view : { ...view, journey: { ...view.journey!, ...options.journey } });
        store.error.set(options.error ?? null);
        go = vi.fn(() => Promise.resolve());
        TestBed.configureTestingModule({
            providers: [provideRouter([{ path: '**', children: [] }]),
                { provide: JourneyStore, useValue: store }, { provide: PathNavigation, useValue: { go, busy: signal(false) } },
                { provide: OnboardingFlow, useValue: { busy: signal(false), replay: vi.fn() } },
                { provide: AuthService, useValue: { user: signal({ accountId: 'acc-1' }), status: signal('authenticated') } }]
        });
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
        const fixture = TestBed.createComponent(PathPageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return { fixture, root, rows: (): HTMLElement[] => Array.from(root.querySelectorAll('app-path-milestone')) };
    };

    beforeEach(() => localStorage.clear());
    afterEach(() => localStorage.clear());

    it('has the eyebrow back to the profile, the title, the intro and the total with a named progress bar', async () => {
        const { root } = await render();
        const eyebrow = root.querySelector('a.eyebrow')!;
        expect(eyebrow.textContent).toBe('Профиль');
        expect(eyebrow.getAttribute('href')).toBe('/profile');
        const heading = root.querySelector('h1')!;
        expect(heading.textContent).toBe('Путь Mnema');
        expect(heading.getAttribute('tabindex')).toBe('-1');
        expect(root.querySelector('section')?.getAttribute('aria-labelledby')).toBe(heading.id);
        expect(root.querySelector('.heading .hint')?.textContent).toBe('Что умеет Mnema. Пункт отмечается, когда вы делаете это на самом деле.');
        expect(root.querySelector('.total strong')?.textContent).toBe('5 из 12');
        const bar = root.querySelector('.total [role="progressbar"]')!;
        expect(bar.getAttribute('aria-label')).toBe('Путь Mnema');
        expect(bar.getAttribute('aria-valuenow')).toBe('5');
        expect(bar.getAttribute('aria-valuemax')).toBe('12');
        expect(bar.getAttribute('aria-valuetext')).toBe('5 из 12');
    });

    it('sets the document title and gives it back when the page goes', async () => {
        document.title = 'Mnema';
        const { fixture } = await render();
        expect(TestBed.inject(Title).getTitle()).toBe('Путь Mnema');
        fixture.destroy();
        expect(document.title).toBe('Mnema');
    });

    it('lists the four chapters as disclosures with «N из M», the current chapter (the first with something left) open', async () => {
        const { root } = await render();
        const chapters = Array.from(root.querySelectorAll('details.chapter')) as HTMLDetailsElement[];
        expect(chapters.map(chapter => chapter.querySelector('.chapter-name')!.textContent)).toEqual(['Начало', 'Материалы', 'Упражнения и ИИ', 'Ритм и Сообщество']);
        expect(chapters.map(chapter => chapter.querySelector('.count')!.textContent)).toEqual(['3 из 3', '1 из 3', '0 из 4', '1 из 2']);
        expect(chapters.map(chapter => chapter.open)).toEqual([false, true, false, false]);
        expect(chapters.every(chapter => chapter.querySelector('summary') !== null)).toBe(true);
    });

    it('keeps a chapter open or folded as the learner left it when a refresh moves the current chapter', async () => {
        const { root, fixture } = await render();
        const chapters = (): HTMLDetailsElement[] => Array.from(root.querySelectorAll('details.chapter'));
        expect(chapters().map(chapter => chapter.open)).toEqual([false, true, false, false]);
        // The learner opens another chapter and folds the current one.
        chapters()[0].open = true;
        chapters()[1].open = false;
        // The refresh: «Материалы» is done, so the current chapter is now «Упражнения».
        store.view.set({ ...midway, milestones: midway.milestones.map(item => ['CAPTURE_NOTE', 'MEDIA_IN_MATERIAL'].includes(item.key)
            ? { ...item, state: 'DONE', progress: item.target, achievedAt: '2026-10-22T10:00:00Z' } : item), path: { ...midway.path!, done: 7 } } as OnboardingView);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(chapters().map(chapter => chapter.open)).toEqual([true, false, false, false]);
    });

    it('has the title as the h1, the chapters as h2 inside their summaries and the milestones as h3: no level is skipped', async () => {
        const { root } = await render();
        expect(root.querySelectorAll('h1')).toHaveLength(1);
        const headings = Array.from(root.querySelectorAll('h1, h2, h3')).map(heading => heading.tagName);
        expect(headings.slice(0, 5)).toEqual(['H1', 'H2', 'H3', 'H3', 'H3']);
        expect(root.querySelectorAll('summary > h2.chapter-name')).toHaveLength(4);
        expect(root.querySelectorAll('h2')).toHaveLength(4);
        for (const [index, heading] of headings.entries()) if (index > 0) expect(Number(heading[1]) - Number(headings[index - 1][1]), `${index}`).toBeLessThanOrEqual(1);
    });

    it('says the total once: the text is for the eyes, the progress bar names and values it for assistive technology', async () => {
        const { root } = await render();
        expect(root.querySelector('.total strong')?.getAttribute('aria-hidden')).toBe('true');
        expect(root.querySelector('.total [role="progressbar"]')?.getAttribute('aria-valuetext')).toBe('5 из 12');
    });

    it('draws the visible milestones only: a hidden one is not in the page', async () => {
        const { root, rows } = await render();
        expect(rows()).toHaveLength(12);
        expect(root.textContent).not.toContain('Проведите неделю в ритме');
        expect(root.textContent).toContain('Возьмите готовую колоду');
        const { root: fresh8, rows: rows8 } = await (async () => { TestBed.resetTestingModule(); return render(fresh); })();
        expect(rows8()).toHaveLength(8);
        expect(fresh8.textContent).not.toContain('Объясните своими словами');
        expect(fresh8.textContent).not.toContain('Возьмите готовую колоду');
        expect(Array.from(fresh8.querySelectorAll('details.chapter .chapter-name')).map(name => name.textContent)).toEqual(['Начало', 'Материалы', 'Упражнения и ИИ', 'Ритм и Сообщество']);
    });

    it('passes every state to the milestone with a word, so nothing rests on colour', async () => {
        const { rows } = await render();
        const words = rows().map(row => row.querySelector('.word')!.textContent!.trim());
        expect(words).toEqual(['Сделано', 'Сделано', 'Сделано', 'Сделано', 'Не начато', 'Начато', 'Начато', 'Не начато', 'Не начато', 'Не начато', 'Сделано', 'Не начато']);
        // Done milestones have no chevron; the others link to a place.
        expect(rows().filter(row => row.querySelector('a.go') === null)).toHaveLength(5);
    });

    it('follows a chevron through the navigation with the in-place hint, by the milestone key', async () => {
        const { rows } = await render();
        const row = rows().find(candidate => candidate.textContent!.includes('Добавьте картинку или звук'))!;
        const link = row.querySelector('a.go') as HTMLAnchorElement;
        expect(link.getAttribute('aria-label')).toBe('Перейти: Добавьте картинку или звук');
        expect(link.getAttribute('href')).toBe('/decks');
        link.click();
        expect(go).toHaveBeenCalledExactlyOnceWith('MEDIA_IN_MATERIAL');
    });

    it('links the milestones that do not depend on a deck straight to their page', async () => {
        const { rows } = await render({ ...fresh, milestones: fresh.milestones.map(milestone => ({ ...milestone })) });
        const hrefs = rows().map(row => row.querySelector('a.go')?.getAttribute('href'));
        expect(hrefs).toEqual(['/profile', '/decks', '/decks', '/decks', '/decks', '/decks', '/decks', '/decks']);
    });

    const existing = { eligible: false, autoOffer: false, state: 'NOT_OFFERED' as const };


    it('shows the «Новое» notice once per account on this device, for an old account (eligible=false)', async () => {
        const first = await render(midway, { journey: existing });
        expect(first.root.querySelector('.path-new .stamp')?.textContent).toBe('Новое');
        expect(first.root.querySelector('.path-new')?.textContent).toContain('«Путь Mnema» — что умеет Mnema. Часть пунктов уже отмечена по вашей истории.');
        expect(localStorage.getItem(`${PATH_NOTICE_STORAGE_PREFIX}acc-1`)).toBe('1');
        // Still there for this visit; gone on the next.
        first.fixture.detectChanges();
        expect(first.root.querySelector('.path-new')).not.toBeNull();
        TestBed.resetTestingModule();
        const again = await render(midway, { journey: existing });
        expect(again.root.querySelector('.path-new')).toBeNull();
    });

    it('shows the notice to the holdout (eligible, still OFFERED, never offered automatically), who never met «Путь» in a tour', async () => {
        const { root } = await render(midway, { journey: { eligible: true, autoOffer: false, state: 'OFFERED' } });
        expect(root.querySelector('.path-new')).not.toBeNull();
        expect(neverOffered({ ...(midway.journey as Journey), eligible: true, autoOffer: false, state: 'OFFERED' })).toBe(true);
    });

    it.each([
        ['a fresh auto-offer', { eligible: true, autoOffer: true, state: 'OFFERED' as const }],
        ['an account that is going through the tour', { eligible: true, autoOffer: false, state: 'IN_PROGRESS' as const }],
        ['an eligible account that finished the tour', { eligible: true, autoOffer: false, state: 'COMPLETED' as const }],
        ['an eligible account that skipped the welcome', { eligible: true, autoOffer: false, state: 'SKIPPED' as const }]
    ])('does not show the notice to %s (the tour was offered to it by itself)', async (_name, journeyState) => {
        const { root } = await render(midway, { journey: journeyState });
        expect(root.querySelector('.path-new')).toBeNull();
        expect(localStorage.getItem(`${PATH_NOTICE_STORAGE_PREFIX}acc-1`)).toBeNull();
    });

    it('hides and shows «Путь» as a preference of this browser', async () => {
        const { root, fixture } = await render();
        const toggle = (): HTMLButtonElement => root.querySelector('.actions button.quiet') as HTMLButtonElement;
        expect(toggle().textContent).toBe('Скрыть «Путь»');
        toggle().click();
        fixture.detectChanges();
        expect(toggle().textContent).toBe('Показывать «Путь»');
        expect(TestBed.inject(PathPreferences).hidden()).toBe(true);
        expect(localStorage.getItem(`${PATH_HIDDEN_STORAGE_PREFIX}acc-1`)).toBe('1');
        toggle().click();
        fixture.detectChanges();
        expect(toggle().textContent).toBe('Скрыть «Путь»');
        expect(TestBed.inject(PathPreferences).hidden()).toBe(false);
    });

    it('offers «Пройти знакомство заново» (the replay of the tour)', async () => {
        const { root } = await render();
        expect(root.querySelector('app-replay-tour button')?.textContent).toBe('Пройти знакомство заново');
        expect(ReplayTourComponent).toBeDefined();
    });

    it('reads the view again when the page opens (a held validator costs a 304)', async () => {
        await render();
        expect(store.load).toHaveBeenCalledWith({ refresh: true });
    });

    describe('when the view cannot be read', () => {
        it('says so with «Повторить», and the retry reads again', async () => {
            const { root, fixture } = await render(null, { error: 'load' });
            expect(root.querySelector('h1')?.textContent).toBe('Путь Mnema');
            expect(root.querySelector('.notice.error')?.getAttribute('role')).toBe('alert');
            expect(root.querySelector('.notice.error p')?.textContent).toBe('Не удалось загрузить «Путь».');
            expect(root.querySelector('details')).toBeNull();
            store.load.mockClear();
            (root.querySelector('.notice.error button') as HTMLButtonElement).click();
            expect(store.load).toHaveBeenCalledWith({ refresh: true });
            expect(fixture.componentInstance).toBeTruthy();
        });

        it('says it is loading before the first answer', async () => {
            const { root } = await render(null);
            expect(root.querySelector('[role="status"]')?.textContent).toBe('Загружаем «Путь»…');
        });

        it('keeps showing the view it holds when a later read fails', async () => {
            const { root } = await render(midway, { error: 'load' });
            expect(root.querySelector('.notice.error')).toBeNull();
            expect(root.querySelectorAll('app-path-milestone')).toHaveLength(12);
        });
    });

    describe('while onboarding is off (the rollback switch)', () => {
        it('draws nothing of «Путь» and sends the learner back to the profile', async () => {
            const { root } = await render(viewOf(null));
            expect(root.querySelector('section, h1, details')).toBeNull();
            expect(router.navigateByUrl).toHaveBeenCalledExactlyOnceWith('/profile', { replaceUrl: true });
        });

        it('does not redirect before the first answer', async () => {
            await render(null);
            expect(router.navigateByUrl).not.toHaveBeenCalled();
        });
    });
});

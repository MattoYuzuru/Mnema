import { TestBed } from '@angular/core/testing';

import { JourneyStore } from '../../core/onboarding/journey.store';
import { Journey, JourneyResult, OnboardingView, Rhythm } from '../../core/onboarding/onboarding.models';
import { FakeJourneyStore, RHYTHM_VIEW } from './onboarding-test-data';
import { ProfileRhythmComponent } from './profile-rhythm.component';

describe('ProfileRhythmComponent (the rhythm block of the profile)', () => {
    const RHYTHM = RHYTHM_VIEW.rhythm as Rhythm;
    let store: FakeJourneyStore;

    function configure(journey: Partial<Journey> | null = { ...(RHYTHM_VIEW.journey as Journey), rowVersion: '0' }, overrides: Partial<OnboardingView> = { rhythm: RHYTHM }): void {
        store = new FakeJourneyStore(journey, overrides);
        TestBed.configureTestingModule({ providers: [{ provide: JourneyStore, useValue: store }] });
    }

    async function render(): Promise<{ root: HTMLElement; detect: () => Promise<void> }> {
        const fixture = TestBed.createComponent(ProfileRhythmComponent);
        document.body.appendChild(fixture.nativeElement as HTMLElement);
        const detect = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
        await detect();
        return { root: fixture.nativeElement as HTMLElement, detect };
    }
    afterEach(() => { document.body.innerHTML = ''; });

    const radios = (root: HTMLElement, name: string): HTMLInputElement[] => [...root.querySelectorAll<HTMLInputElement>(`input[type=radio][name=${name}]`)];
    const save = (root: HTMLElement): HTMLButtonElement => root.querySelector<HTMLButtonElement>('button[type=submit]')!;
    const status = (root: HTMLElement): string => root.querySelector('[role=status]')?.textContent?.trim() ?? '';

    it('shows the week, the target and the weeks in rhythm under the heading, as a named section the milestone of «Путь» points at', async () => {
        configure();
        const { root } = await render();
        const section = root.querySelector('section')!;
        expect(section.id).toBe('profile-rhythm');
        expect(section.getAttribute('data-tour-anchor')).toBe('profile.rhythm');
        expect(root.querySelector('h2')?.textContent).toBe('Недельный ритм');
        expect(section.getAttribute('aria-labelledby')).toBe(root.querySelector('h2')?.id);
        expect(root.querySelector('app-rhythm-week p.visually-hidden')?.textContent).toBe('Эта неделя: занятия в понедельник, во вторник и в четверг.');
        expect(root.querySelector('.target')?.textContent).toBe('Цель — 5 дней в неделю');
        expect(root.querySelector('.weeks')?.textContent).toBe('Недель в ритме: 2');
    });

    it('is the only place that says the weeks in rhythm, and says nothing of a count of zero', async () => {
        configure(undefined, { rhythm: { ...RHYTHM, weeksMet: 0 } });
        const { root } = await render();
        expect(root.querySelector('.weeks')).toBeNull();
        expect(root.textContent).not.toContain('Недель в ритме');
        expect(root.querySelector('.target')).not.toBeNull();
    });

    it.each([
        ['onboarding is off', null, {}],
        ['the view carries no rhythm', {}, { rhythm: null }]
    ] as const)('draws nothing when %s', async (_name, journey, overrides) => {
        configure(journey as Partial<Journey> | null, overrides as Partial<OnboardingView>);
        const { root } = await render();
        expect(root.querySelector('section')).toBeNull();
    });

    it('offers days a week from 1 to 7 and the three answers of the survey, each in a named group, starting at the saved ones', async () => {
        configure({ ...(RHYTHM_VIEW.journey as Journey), experience: 'FAMILIAR' });
        const { root } = await render();
        const legends = [...root.querySelectorAll('legend')].map(legend => legend.textContent);
        expect(legends).toEqual(['Сколько дней в неделю хотите заниматься?', 'Учились ли вы по карточкам раньше?']);
        expect(radios(root, 'profile-rhythm-days').map(radio => radio.closest('label')?.textContent?.trim())).toEqual(
            ['1 день', '2 дня', '3 дня', '4 дня', '5 дней', '6 дней', 'Каждый день']);
        expect(radios(root, 'profile-rhythm-days').find(radio => radio.checked)?.value).toBe('5');
        expect(radios(root, 'profile-rhythm-experience').map(radio => radio.closest('label')?.textContent?.trim())).toEqual(['Впервые', 'Да, в похожих приложениях', 'Да, уверенно']);
        expect(radios(root, 'profile-rhythm-experience').find(radio => radio.checked)?.value).toBe('FAMILIAR');
    });

    it('has nothing to save until something changed: the button says so (aria-disabled, never disabled) and a press sends nothing', async () => {
        configure();
        const { root, detect } = await render();
        expect(save(root).getAttribute('aria-disabled')).toBe('true');
        expect(save(root).disabled).toBe(false);
        save(root).click();
        await detect();
        expect(store.setPreferences).not.toHaveBeenCalled();
        // Choosing the saved value again changes nothing either.
        radios(root, 'profile-rhythm-days')[4].click();
        await detect();
        expect(save(root).getAttribute('aria-disabled')).toBe('true');
    });

    it('saves a new target through the preferences command of the journey, says so politely, and keeps the focus on the button', async () => {
        configure();
        const { root, detect } = await render();
        radios(root, 'profile-rhythm-days')[2].click();
        await detect();
        expect(save(root).getAttribute('aria-disabled')).toBeNull();
        save(root).focus();
        save(root).click();
        await detect();
        expect(store.setPreferences).toHaveBeenCalledExactlyOnceWith({ weeklyTarget: 3 });
        expect(status(root)).toBe('Сохранено.');
        expect(root.querySelector('[role=status]')?.closest('form')).not.toBeNull();
        expect(document.activeElement).toBe(save(root));
        // The block follows the view the server answered: the new target, the same week.
        expect(root.querySelector('.target')?.textContent).toBe('Цель — 3 дня в неделю');
        expect(radios(root, 'profile-rhythm-days').find(radio => radio.checked)?.value).toBe('3');
        expect(save(root).getAttribute('aria-disabled')).toBe('true');
    });

    it('saves the experience alone, and both answers together, sending only what changed', async () => {
        configure({ ...(RHYTHM_VIEW.journey as Journey), experience: 'NEW' });
        const { root, detect } = await render();
        radios(root, 'profile-rhythm-experience')[2].click();
        await detect();
        save(root).click();
        await detect();
        expect(store.setPreferences).toHaveBeenLastCalledWith({ experience: 'CONFIDENT' });
        expect(store.journey()?.experience).toBe('CONFIDENT');
        radios(root, 'profile-rhythm-days')[6].click();
        radios(root, 'profile-rhythm-experience')[1].click();
        await detect();
        save(root).click();
        await detect();
        expect(store.setPreferences).toHaveBeenLastCalledWith({ weeklyTarget: 7, experience: 'FAMILIAR' });
        expect(store.preferences).toHaveLength(2);
    });

    it('saves for an account that was never offered the tour (no row yet: version 0)', async () => {
        configure({ ...(RHYTHM_VIEW.journey as Journey), state: 'NOT_OFFERED', rowVersion: '0' });
        const { root, detect } = await render();
        radios(root, 'profile-rhythm-days')[0].click();
        await detect();
        save(root).click();
        await detect();
        expect(store.rejections).toEqual([]);
        expect(store.journey()).toMatchObject({ state: 'NOT_OFFERED', weeklyTarget: 1, rowVersion: '1' });
        expect(status(root)).toBe('Сохранено.');
        expect(root.querySelector('.target')?.textContent).toBe('Цель — 1 день в неделю');
    });

    it('keeps the choices and says so when the version was stale (412): the view is read again and the next press saves', async () => {
        configure();
        const { root, detect } = await render();
        store.setPreferences.mockImplementationOnce((): Promise<JourneyResult> => {
            // The server holds a newer row: the re-read changed the saved target under the learner.
            const current = store.view()!;
            const next = { ...current, journey: { ...current.journey!, weeklyTarget: 2, rowVersion: '4' }, rhythm: { ...RHYTHM, target: 2 } };
            store.view.set(next);
            return Promise.resolve({ status: 'refused', view: next });
        });
        radios(root, 'profile-rhythm-days')[2].click();
        radios(root, 'profile-rhythm-experience')[1].click();
        await detect();
        save(root).focus();
        save(root).click();
        await detect();
        const notice = root.querySelector('.notice')!;
        expect(notice.getAttribute('role')).toBe('alert');
        expect(notice.textContent).toBe('Настройки уже изменились на другом устройстве. Проверьте выбор и сохраните ещё раз.');
        expect(notice.classList.contains('warning')).toBe(true);
        expect(status(root)).toBe('');
        // The learner's draft is not thrown away by the re-read.
        expect(radios(root, 'profile-rhythm-days').find(radio => radio.checked)?.value).toBe('3');
        expect(radios(root, 'profile-rhythm-experience').find(radio => radio.checked)?.value).toBe('FAMILIAR');
        expect(document.activeElement).toBe(save(root));
        save(root).click();
        await detect();
        expect(store.setPreferences).toHaveBeenLastCalledWith({ weeklyTarget: 3, experience: 'FAMILIAR' });
        expect(root.querySelector('.notice')).toBeNull();
        expect(status(root)).toBe('Сохранено.');
    });

    it('says that saving failed as an alert, keeps the choices, and lets the learner try again', async () => {
        configure();
        const { root, detect } = await render();
        radios(root, 'profile-rhythm-days')[1].click();
        await detect();
        store.failNext = true;
        save(root).click();
        await detect();
        const notice = root.querySelector('.notice')!;
        expect(notice.getAttribute('role')).toBe('alert');
        expect(notice.classList.contains('error')).toBe(true);
        expect(notice.textContent).toBe('Не удалось сохранить. Попробуйте ещё раз.');
        expect(radios(root, 'profile-rhythm-days').find(radio => radio.checked)?.value).toBe('2');
        expect(store.journey()?.weeklyTarget).toBe(5);
        save(root).click();
        await detect();
        expect(store.journey()?.weeklyTarget).toBe(2);
        expect(root.querySelector('.notice')).toBeNull();
    });

    it('clears the notice and the status when the learner starts another change, and sends one request at a time', async () => {
        configure();
        const { root, detect } = await render();
        radios(root, 'profile-rhythm-days')[1].click();
        await detect();
        save(root).click();
        await detect();
        expect(status(root)).toBe('Сохранено.');
        radios(root, 'profile-rhythm-days')[3].click();
        await detect();
        expect(status(root)).toBe('');
        let release!: (result: JourneyResult) => void;
        store.setPreferences.mockImplementationOnce(() => new Promise<JourneyResult>(resolve => { release = resolve; }));
        save(root).click();
        save(root).click();
        await detect();
        expect(store.setPreferences).toHaveBeenCalledTimes(2);
        expect(save(root).getAttribute('aria-disabled')).toBe('true');
        // The groups are locked while the request is on its way.
        radios(root, 'profile-rhythm-days')[0].click();
        await detect();
        release({ status: 'applied', view: store.view()! });
        await detect();
        expect(radios(root, 'profile-rhythm-days').find(radio => radio.checked)?.value).toBe('2');
    });

    it('uses no word of loss or pressure', async () => {
        configure();
        const { root } = await render();
        expect(root.textContent).not.toMatch(/пропущ(?!енный день ничего не сжигает)|потер|долг|серия|стрик|огонь/iu);
    });
});

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { AuthService } from '../../auth.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { UsageApiService } from '../usage/usage-api.service';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { CaptureNote } from '../authoring/authoring.models';
import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from '../authoring/capabilities-api.service';
import { GenerationApiService } from './generation-api.service';
import { ESTIMATE_DEBOUNCE_MS, GenerationComposerComponent, buildMaterialsSpec } from './generation-composer.component';
import { ComposerSource } from './note-sources';
import { DEFAULT_SETTINGS } from './generation-settings.component';
import { GenerationEstimate, MaterialsSpec, SessionDetail, parseEstimate, parseSessionDetail } from './generation.models';
import { NBSP } from './generation-view';
import { clone, examples, ids, problemResponse, usageContract } from './generation-test-data';

describe('GenerationComposerComponent', () => {
    let fixture: ComponentFixture<GenerationComposerComponent>;
    let api: SpyObj<GenerationApiService>;
    let authoring: SpyObj<AuthoringApiService>;
    let user: ReturnType<typeof signal<{ displayName: string | null; profileUsername: string | null; email: string } | null>>;
    const created = parseSessionDetail(examples['sessionDetailCreated']);
    const affordable = (): GenerationEstimate => parseEstimate(usageContract['estimateResponse']);
    const short = (): GenerationEstimate => parseEstimate(usageContract['estimateResponseShortfall']);
    const capabilities = (overrides: Partial<LearningCapabilities> = {}): LearningCapabilities => ({
        ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null }, ...overrides });

    const noteOf = (noteId: string, rowVersion: string, patch: Partial<CaptureNote> = {}): CaptureNote => ({
        noteId, deckId: ids.deckId, rowVersion, source: 'manual', text: 'Заметка', contentBytes: 14, archived: false,
        createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-01T09:00:00Z', conversion: null, ...patch });

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const field = (): HTMLTextAreaElement => root().querySelector('textarea')!;
    const button = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('button.generate-cta')!;
    const estimateText = (): string => root().querySelector('.estimate')!.textContent!.trim();
    const render = (): void => fixture.detectChanges();

    function type(text: string): void {
        field().value = text;
        field().dispatchEvent(new Event('input', { bubbles: true }));
        render();
    }

    function key(init: KeyboardEventInit & { keyCode?: number }): KeyboardEvent {
        const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
        if (init.keyCode !== undefined) Object.defineProperty(event, 'keyCode', { value: init.keyCode });
        field().dispatchEvent(event);
        render();
        return event;
    }

    function create(options: { sources?: ComposerSource[]; caps?: LearningCapabilities; name?: string | null } = {}): void {
        vi.useFakeTimers();
        api = spyObj<GenerationApiService>({ estimate: vi.fn().mockName('estimate'), createSession: vi.fn().mockName('createSession'),
            listActiveSessions: vi.fn().mockName('listActiveSessions') });
        api.estimate.mockReturnValue(of(affordable()));
        api.createSession.mockReturnValue(of({ session: created, replayed: false }));
        authoring = spyObj<AuthoringApiService>({ readCapture: vi.fn().mockName('readCapture') });
        authoring.readCapture.mockImplementation((noteId: string) => of(noteOf(noteId, '3')));
        user = signal(options.name === null ? null : { displayName: options.name ?? 'Юзуру Мацуда', profileUsername: 'yuzuru', email: 'yuzuru@example.test' });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api }, { provide: AuthoringApiService, useValue: authoring },
            { provide: UsageApiService, useValue: { load: () => of({ credits: { total: 360 } }) } },
            { provide: AuthService, useValue: { user } }] });
        fixture = TestBed.createComponent(GenerationComposerComponent);
        fixture.componentRef.setInput('deckId', ids.deckId);
        fixture.componentRef.setInput('deckTitle', 'Японский N4');
        fixture.componentRef.setInput('capabilities', options.caps ?? capabilities());
        if (options.sources !== undefined) fixture.componentRef.setInput('sources', options.sources);
        render();
    }

    afterEach(() => vi.useRealTimers());

    describe('the page', () => {
        it('greets by first name in the visible label of the request field, and without a name when there is none', () => {
            create();
            const label = root().querySelector<HTMLLabelElement>('h1 label')!;
            expect(label.textContent).toBe('Юзуру, что будем учить сегодня?');
            expect(label.htmlFor).toBe(field().id);
            expect(root().querySelector('h1')?.getAttribute('tabindex')).toBe('-1');
            expect(root().querySelector('.eyebrow')?.textContent).toContain('Японский N4');
            TestBed.resetTestingModule();
            create({ name: null });
            expect(root().querySelector('h1 label')?.textContent).toBe('Что будем учить сегодня?');
            user.set({ displayName: null, profileUsername: 'yuzuru', email: 'yuzuru@example.test' });
            render();
            expect(root().querySelector('h1 label')?.textContent).toBe('yuzuru, что будем учить сегодня?');
            user.set({ displayName: '  ', profileUsername: null, email: 'yuzuru@example.test' });
            render();
            expect(root().querySelector('h1 label')?.textContent).toBe('Что будем учить сегодня?');
            expect(root().textContent).not.toContain('example.test');
        });

        it('has one generate button, the two ways out (to the deck and to the plain editor) and the «Что это?» toggletip', () => {
            create();
            expect(root().querySelectorAll('.generate-cta')).toHaveLength(1);
            expect(button().type).toBe('submit');
            expect(root().querySelector('a.back-link')?.getAttribute('href')).toBe(`/decks/${ids.deckId}`);
            const editor = root().querySelector<HTMLAnchorElement>('.write-myself a')!;
            expect(editor.textContent).toContain('Или откройте пустой редактор');
            expect(editor.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/new?write=1`);
            expect(root().querySelector('.what-label')?.textContent).toBe('Что это?');
            expect(root().querySelector('app-toggletip button')?.getAttribute('aria-label')).toBe('Подробнее: Мнема и ИИ');
            expect(field().getAttribute('enterkeyhint')).toBe('send');
            expect(field().maxLength).toBe(2000);
            expect(field().placeholder).toContain('Например');
        });
    });

    describe('the placeholder', () => {
        it('invites a request without notes, and says the request is optional with a note chip', () => {
            create();
            expect(field().placeholder).toMatch(/^Например/u);
            TestBed.resetTestingModule();
            create({ sources: [{ label: 'Глаголы', spec: { role: 'SOURCE', type: 'NOTE', noteId: ids.first, noteRowVersion: '3' } }] });
            expect(field().placeholder).toMatch(/^Необязательно/u);
        });
    });

    describe('sending with the keyboard', () => {
        it('sends on Enter and keeps Shift+Enter for a new line', () => {
            create();
            type('Объясни Seq Scan');
            expect(key({ key: 'Enter', shiftKey: true }).defaultPrevented).toBe(false);
            expect(api.createSession).not.toHaveBeenCalled();
            expect(key({ key: 'Enter' }).defaultPrevented).toBe(true);
            expect(api.createSession).toHaveBeenCalledTimes(1);
        });

        it('never sends while an IME composes: not on isComposing, not on the legacy keyCode 229', () => {
            create();
            type('日本語');
            expect(key({ key: 'Enter', isComposing: true }).defaultPrevented).toBe(false);
            expect(key({ key: 'Enter', keyCode: 229 }).defaultPrevented).toBe(false);
            expect(api.createSession).not.toHaveBeenCalled();
            // The confirming Enter of the candidate is over: the next one sends.
            key({ key: 'Enter', isComposing: false });
            expect(api.createSession).toHaveBeenCalledTimes(1);
        });

        it('never lets Enter on a checkbox or a radio of the form start a session (implicit submission)', () => {
            create();
            type('Объясни Seq Scan');
            const prevented = (target: Element) => {
                const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
                target.dispatchEvent(event);
                return event.defaultPrevented;
            };
            expect(prevented(root().querySelector('input[type=radio]')!)).toBe(true);
            const checkbox = root().querySelector('input[type=checkbox]');
            expect(checkbox).not.toBeNull();
            expect(prevented(checkbox!)).toBe(true);
            expect(prevented(button())).toBe(false);
            expect(api.createSession).not.toHaveBeenCalled();
        });

        it('asks for a request instead of sending an empty one, and moves focus to the field', () => {
            create();
            document.body.append(root());
            key({ key: 'Enter' });
            expect(api.createSession).not.toHaveBeenCalled();
            const error = root().querySelector('.field-error')!;
            expect(error.getAttribute('role')).toBe('alert');
            expect(field().getAttribute('aria-invalid')).toBe('true');
            expect(field().getAttribute('aria-describedby')).toContain(error.id);
            expect(document.activeElement).toBe(field());
            type('x');
            expect(root().querySelector('.field-error')).toBeNull();
            root().remove();
        });
    });

    describe('preflight', () => {
        it('stays silent until there is something to estimate, then asks once, 400 ms after the last change', () => {
            create();
            vi.advanceTimersByTime(1000);
            expect(api.estimate).not.toHaveBeenCalled();
            expect(estimateText()).toBe('');
            type('о');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS - 100);
            type('об');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS - 100);
            type('объясни');
            expect(estimateText()).toBe('Считаем…');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS - 1);
            expect(api.estimate).not.toHaveBeenCalled();
            vi.advanceTimersByTime(1);
            render();
            expect(api.estimate).toHaveBeenCalledTimes(1);
            expect(api.estimate.mock.calls[0]![0]).toBe(ids.deckId);
            expect(api.estimate.mock.calls[0]![1]).toMatchObject({ kind: 'MATERIALS', prompt: 'объясни' });
            expect(estimateText()).toBe(`≈${NBSP}6${NBSP}% лимита`);
        });

        it('asks again when a setting changes, and drops the answer still in flight', () => {
            create();
            const first = new Subject<GenerationEstimate>();
            api.estimate.mockReturnValueOnce(first);
            type('объясни');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            const radios = [...root().querySelectorAll<HTMLInputElement>('input[type=radio]')];
            radios[1]!.click();
            render();
            expect(first.observed).toBe(false);
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            expect(api.estimate).toHaveBeenCalledTimes(2);
            expect((api.estimate.mock.calls[1]![1] as MaterialsSpec).settings.effort).toBe('SHORT');
        });

        it('says it could not estimate and lets the user go on', () => {
            create();
            api.estimate.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            type('объясни');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            expect(estimateText()).toBe('Оценить не удалось, но запустить можно.');
            button().click();
            expect(api.createSession).toHaveBeenCalledTimes(1);
        });

        it('mentions personal data the server found in the request, and only then', () => {
            create();
            type('объясни');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            expect(root().textContent).not.toContain('личные данные');
            api.estimate.mockReturnValue(of({ ...affordable(), personalDataWarning: true }));
            type('объясни, мой телефон +7 900 000-00-00');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            expect(root().querySelector('.notice')?.textContent).toContain('личные данные');
        });

        it('returns to silence when the request is cleared', () => {
            create();
            type('объясни');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            type('');
            expect(estimateText()).toBe('');
        });
    });

    describe('a request that does not fit the budget', () => {
        function overBudget(): void {
            create();
            api.estimate.mockReturnValue(of(short()));
            type('объясни');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
        }

        it('keeps the button pressable, says so next to it, and does not call the server when pressed', () => {
            overBudget();
            expect(button().disabled).toBe(false);
            expect(button().getAttribute('aria-disabled')).toBe('true');
            expect(estimateText()).toContain('Не хватит лимита');
            button().click();
            render();
            expect(api.createSession).not.toHaveBeenCalled();
            const notice = root().querySelector('.limit')!;
            expect(notice.textContent).toContain('Не хватит лимита ИИ на этот запрос.');
            expect(notice.textContent).toContain('Выберите «Кратко»');
            expect(notice.textContent).toContain('Подождите до');
            expect(notice.querySelector('a')?.getAttribute('href')).toBe('/profile#ai-budget');
        });

        it('explains from a USAGE_LIMIT_REACHED answer too, with the date of the renewal, and clears the explanation on the next change', () => {
            create();
            api.createSession.mockReturnValue(throwError(() => problemResponse(409, { ...clone(usageContract['errors'].USAGE_LIMIT_REACHED.exampleFreeWeek) })));
            type('объясни');
            button().click();
            render();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            const notice = root().querySelector('.limit')!;
            expect(notice.textContent).toContain('всё равно не хватит');
            expect(button().getAttribute('aria-disabled')).toBeNull();
            type('объясни короче');
            expect(root().querySelector('.limit')).toBeNull();
        });

        it('falls back to a general explanation when the estimate blocks without naming a bucket', () => {
            create();
            api.estimate.mockReturnValue(of({ ...short(), blockingBuckets: [] }));
            type('объясни');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            button().click();
            render();
            expect(root().querySelector('.limit')?.textContent).toContain('Выберите «Кратко»');
            [...root().querySelectorAll<HTMLInputElement>('input[type=radio]')][1]!.click();
            render();
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            button().click();
            render();
            expect(root().querySelector('.limit')?.textContent).toContain('Сократите запрос');
        });
    });

    describe('«Сначала показать план» (#295)', () => {
        const plannedEstimate = (): GenerationEstimate => parseEstimate({ ...clone(usageContract['estimateResponse']),
            credits: { p50: 30, p95: 40 }, percentOfPeriodAllowance: { p50: 8, p95: 11 },
            breakdown: [{ operation: 'SMART_PLAN_FLASH', count: 1, credits: 20 }, { operation: 'MATERIAL_MEDIUM', count: 2, credits: 20 }] });
        const planBox = (): HTMLInputElement => [...root().querySelectorAll<HTMLInputElement>('input[type=checkbox]')].find(input => input.labels?.[0]?.textContent?.trim() === 'Сначала показать план')!;

        it('asks for the plan only when the box is checked: the spec says so, the estimate is asked again and the button makes the plan', () => {
            create();
            type('Объясни Seq Scan');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            expect(api.estimate.mock.calls.at(-1)![1]).toMatchObject({ settings: { planFirst: false } });
            expect(button().textContent?.trim()).toBe('Создать');
            api.estimate.mockReturnValue(of(plannedEstimate()));
            planBox().click();
            render();
            expect(button().textContent?.trim()).toBe('Составить план');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            render();
            expect(api.estimate.mock.calls.at(-1)![1]).toMatchObject({ settings: { planFirst: true } });
            button().click();
            expect(api.createSession.mock.calls[0]![1]).toMatchObject({ settings: { planFirst: true } });
        });

        it('says what the plan costs on its own next to the box, from the plan line of the estimate, and nothing when it is off', async () => {
            create();
            type('Объясни Seq Scan');
            api.estimate.mockReturnValue(of(plannedEstimate()));
            planBox().click();
            render();
            await vi.advanceTimersByTimeAsync(ESTIMATE_DEBOUNCE_MS);
            render();
            // The exact share of the whole allowance (20 of 360), not the rounded percentage of the estimate.
            expect(root().querySelector('.cost')?.textContent?.replace(/\u00a0/g, ' ')).toBe('План: ≈ 5,6 % лимита');
            expect(planBox().getAttribute('aria-describedby')).toContain('-cost');
            expect(estimateText()).toBe(`≈${NBSP}11${NBSP}% лимита`);
            planBox().click();
            render();
            expect(root().querySelector('.cost')).toBeNull();
        });

        it('explains in words that the plans are over or not offered, and that the work can start without one', () => {
            create();
            type('Объясни Seq Scan');
            planBox().click();
            render();
            const bucket = { bucket: 'SMART_PLAN', window: 'MONTH', unit: 'COUNT', limit: 4, used: 4, required: 1, offered: true, renewsAt: '2026-10-31T21:00:00Z', fitsAfterRenewal: true, plan: 'PLUS' };
            api.createSession.mockReturnValue(throwError(() => problemResponse(409, { code: 'USAGE_LIMIT_REACHED', ...bucket })));
            button().click();
            render();
            const text = root().querySelector('.limit')!.textContent!;
            expect(text).toContain('Планы на этот период закончились.');
            expect(text).toContain('Снимите «Сначала показать план»');
            expect(text).not.toContain('Кратко');
            api.createSession.mockReturnValue(throwError(() => problemResponse(409, { code: 'USAGE_LIMIT_REACHED', ...bucket, offered: false, limit: null })));
            button().click();
            render();
            expect(root().querySelector('.limit')!.textContent).toContain('Планы недоступны на вашем тарифе.');
        });
    });

    describe('creating the session', () => {
        it('sends the spec of the screen with a command id (no plan unless the box is checked, see above), and reports the session', () => {
            create();
            const sessions: SessionDetail[] = [];
            fixture.componentInstance.created.subscribe(session => sessions.push(session));
            type('  Объясни Seq Scan  ');
            button().click();
            const [deckId, spec, commandId] = api.createSession.mock.calls[0]!;
            expect(deckId).toBe(ids.deckId);
            expect(spec).toMatchObject({ kind: 'MATERIALS', prompt: 'Объясни Seq Scan', sources: [],
                settings: { effort: 'AUTO', notesMode: 'ONE_PER_NOTE', similarToDeck: true, planFirst: false, factCheck: false, budgetPercent: null,
                    media: { audio: { enabled: false }, imageSearch: false } } });
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/);
            expect(sessions).toEqual([created]);
        });

        it('requests media only where the capability exists: the chip can be on, the request still says no', () => {
            const spec = buildMaterialsSpec('x', { ...DEFAULT_SETTINGS, imageSearch: true, audio: true, audioVoice: 'male' }, [], { image: false, audio: false });
            expect(spec.settings.media).toEqual({ audio: { enabled: false, lang: 'ru', voice: 'male' }, imageSearch: false });
            create({ caps: capabilities({ imageSearch: { available: true, reason: null } }) });
            const [image, audio] = [...root().querySelectorAll<HTMLInputElement>('input[type=checkbox]')];
            expect(image!.disabled).toBe(false);
            expect(audio!.disabled).toBe(true);
        });

        it('shows that it is working, ignores a second press, and reports nothing but the session', () => {
            create();
            const pending = new Subject<{ session: SessionDetail; replayed: boolean }>();
            api.createSession.mockReturnValue(pending);
            type('объясни');
            button().click();
            render();
            expect(button().textContent?.trim()).toBe('Создаём…');
            expect(button().getAttribute('aria-disabled')).toBe('true');
            expect(root().querySelector('[role=status]')?.textContent).toContain('Мнема открывает мастерскую');
            button().click();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            pending.next({ session: created, replayed: false });
            render();
            expect(button().textContent?.trim()).toBe('Создать');
        });

        it('sends the very same command again after an unknown outcome and a new one once the request changes', () => {
            create();
            type('объясни');
            api.createSession.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
            button().click();
            render();
            expect(root().querySelector('.notice.error')?.textContent).toContain('та же команда');
            button().click();
            type('объясни иначе');
            button().click();
            const commands = api.createSession.mock.calls.map(call => call[2]);
            expect(commands[1]).toBe(commands[0]);
            expect(commands[2]).not.toBe(commands[0]);
        });

        it('says what went wrong and offers the editor when the server refuses', () => {
            create();
            api.createSession.mockReturnValue(throwError(() => problemResponse(409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'aiGeneration', reason: 'TEMPORARILY_UNAVAILABLE' })));
            type('объясни');
            button().click();
            render();
            const notice = root().querySelector('.notice.error')!;
            expect(notice.getAttribute('role')).toBe('alert');
            expect(notice.textContent).toContain('ИИ сейчас недоступен');
            expect(notice.querySelector('a')?.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/new?write=1`);
        });

        it('lists the active workshops when the limit of three is reached', () => {
            create();
            api.createSession.mockReturnValue(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'ACTIVE_SESSIONS',
                activeSessionIds: [ids.sessionId] })));
            api.listActiveSessions.mockReturnValue(of({ items: [{ ...parseSessionDetail(examples['sessionDetail']) }], nextCursor: null }));
            type('объясни');
            button().click();
            render();
            const link = root().querySelector<HTMLAnchorElement>('.workshop-links a')!;
            expect(link.getAttribute('href')).toBe(`/decks/${ids.deckId}/workshop/${ids.sessionId}`);
            expect(root().querySelector('.notice.error')?.textContent).toContain('три мастерские');
            api.listActiveSessions.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            button().click();
            render();
            expect(root().querySelector('.workshop-links')).toBeNull();
        });
    });

    describe('sources (the note chips of AI-08)', () => {
        const note: ComposerSource = { label: 'Глаголы движения', spec: { role: 'SOURCE', type: 'NOTE', noteId: '20700000-0000-4000-8000-000000000001', noteRowVersion: '3' } };
        const style: ComposerSource = { label: 'Эталон', spec: { role: 'STYLE_EXAMPLE', type: 'ITEM', memberKey: '44444444-4444-4444-8444-444444444444',
            itemRevisionId: '55555555-5555-4555-8555-555555555555' } };

        it('shows them as removable chips, makes the prompt optional with a SOURCE and sends the pins', () => {
            create({ sources: [note] });
            const chips = root().querySelectorAll('.source-chip');
            expect(chips).toHaveLength(1);
            expect(chips[0]!.textContent).toContain('Глаголы движения');
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            expect(api.estimate).toHaveBeenCalledTimes(1);
            button().click();
            expect(api.createSession.mock.calls[0]![1]).toMatchObject({ prompt: '', sources: [note.spec] });
            const removed: ComposerSource[] = [];
            fixture.componentInstance.sourceRemoved.subscribe(source => removed.push(source));
            root().querySelector<HTMLButtonElement>('.chip-remove')!.click();
            expect(removed).toEqual([note]);
            expect(root().querySelector('.chip-remove')?.getAttribute('aria-label')).toBe('Убрать: Глаголы движения');
        });

        it('still wants a prompt when only an example is pinned', () => {
            create({ sources: [style] });
            vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
            expect(api.estimate).not.toHaveBeenCalled();
            button().click();
            expect(api.createSession).not.toHaveBeenCalled();
        });

        describe('several notes: grouping, per-note settings and the pins read at submit', () => {
            const noteId = (position: number): string => `20700000-0000-4000-8000-00000000000${position}`;
            const noteAt = (position: number): ComposerSource => ({ label: `Заметка ${position}`,
                spec: { role: 'SOURCE', type: 'NOTE', noteId: noteId(position), noteRowVersion: '3' } });
            const effortOf = (position: number) => ({ [noteId(position)]: { effort: 'DETAILED' as const, imageSearch: null, audio: null } });
            const lastSpec = () => api.createSession.mock.calls.at(-1)![1] as MaterialsSpec;
            const wire = (spec: MaterialsSpec) => spec.sources as unknown as { overrides?: unknown; noteRowVersion?: string }[];

            it('offers the grouping only for two or more notes: «Материал на заметку» is the default', () => {
                create({ sources: [noteAt(1)] });
                expect(root().querySelector('app-note-overrides')).toBeNull();
                expect(root().textContent).not.toContain('Как оформить заметки');
                TestBed.resetTestingModule();
                create({ sources: [noteAt(1), noteAt(2)] });
                const radios = Array.from(root().querySelectorAll<HTMLInputElement>('.notes-options input[type=radio]'));
                expect(radios.map(radio => radio.value)).toEqual(['ONE_PER_NOTE', 'MERGE_INTO_ONE']);
                expect(radios.find(radio => radio.checked)?.value).toBe('ONE_PER_NOTE');
                expect(root().textContent).toContain('Материал на заметку');
                expect(root().textContent).toContain('Объединить в один');
                expect(root().querySelector('details.per-note summary')?.textContent).toContain('Настроить для каждой заметки отдельно');
                expect(root().querySelector('details.per-note')?.hasAttribute('open')).toBe(false);
            });

            it('sends the grouping with every note and no overrides by default', () => {
                create({ sources: [noteAt(1), noteAt(2)] });
                button().click();
                expect(lastSpec().settings.notesMode).toBe('ONE_PER_NOTE');
                expect(wire(lastSpec()).map(source => source.overrides)).toEqual([undefined, undefined]);
                TestBed.resetTestingModule();
                create({ sources: [noteAt(1), noteAt(2)] });
                root().querySelector<HTMLInputElement>('.notes-options input[value=MERGE_INTO_ONE]')!.click();
                render();
                button().click();
                expect(lastSpec().settings.notesMode).toBe('MERGE_INTO_ONE');
                expect(lastSpec().sources).toHaveLength(2);
            });

            it('sends sparse overrides for the changed notes only, and the estimate prices them too', () => {
                create({ sources: [noteAt(1), noteAt(2), noteAt(3)] });
                vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
                api.estimate.mockClear();
                fixture.componentInstance.onOverrides({ ...effortOf(2), [noteId(3)]: { effort: null, imageSearch: null, audio: true } });
                render();
                expect(root().querySelector('details.per-note .count')?.textContent).toContain('2 заметки настроены отдельно');
                vi.advanceTimersByTime(ESTIMATE_DEBOUNCE_MS);
                const estimated = wire(api.estimate.mock.calls.at(-1)![1] as MaterialsSpec);
                expect(estimated[0]!.overrides).toBeUndefined();
                expect(estimated[1]!.overrides).toEqual({ effort: 'DETAILED' });
                button().click();
                const sent = wire(lastSpec());
                expect(sent[0]!.overrides).toBeUndefined();
                expect(sent[1]!.overrides).toEqual({ effort: 'DETAILED' });
                // Audio is a capability the caps of this test do not offer: the override is dropped, not sent as a request the server refuses.
                expect(sent[2]!.overrides).toBeUndefined();
            });

            it('sends an audio override with the session language when speech is available', () => {
                create({ sources: [noteAt(1), noteAt(2)], caps: capabilities({ textToSpeech: { available: true, reason: null } }) });
                fixture.componentInstance.onOverrides({ [noteId(2)]: { effort: null, imageSearch: null, audio: true } });
                render();
                button().click();
                expect((wire(lastSpec()))[1]!.overrides)
                    .toEqual({ media: { audio: { enabled: true, lang: 'ru', voice: null } } });
            });

            it('drops every per-note setting when merging, says so, and never sends an empty overrides object', () => {
                create({ sources: [noteAt(1), noteAt(2)] });
                fixture.componentInstance.onOverrides(effortOf(1));
                render();
                root().querySelector<HTMLInputElement>('.notes-options input[value=MERGE_INTO_ONE]')!.click();
                render();
                expect(fixture.componentInstance.overrides()).toEqual({});
                expect(root().querySelector('app-note-overrides')).toBeNull();
                expect(root().querySelector('.notes-options .cleared-note')?.textContent).toContain('сброшены');
                button().click();
                expect(JSON.stringify(lastSpec())).not.toContain('overrides');
                root().querySelector<HTMLInputElement>('.notes-options input[value=ONE_PER_NOTE]')!.click();
                render();
                expect(root().querySelector('.notes-options .cleared-note')).toBeNull();
                expect(root().querySelector('app-note-overrides')).not.toBeNull();
            });

            it('ignores overrides of a single note: it has no one to differ from', () => {
                create({ sources: [noteAt(1)] });
                fixture.componentInstance.onOverrides(effortOf(1));
                button().click();
                expect(JSON.stringify(lastSpec())).not.toContain('overrides');
            });

            it('reads every note again and pins the version that is current at submit time', () => {
                create({ sources: [noteAt(1), noteAt(2)] });
                authoring.readCapture.mockImplementation((id: string) => of(noteOf(id, id === noteId(2) ? '9' : '3')));
                button().click();
                expect(authoring.readCapture).toHaveBeenCalledTimes(2);
                expect(wire(lastSpec()).map(source => source.noteRowVersion)).toEqual(['3', '9']);
            });

            it('names a note that was archived or moved, takes it off the list and creates nothing', () => {
                create({ sources: [noteAt(1), noteAt(2)] });
                const removed: ComposerSource[] = [];
                fixture.componentInstance.sourceRemoved.subscribe(source => removed.push(source));
                authoring.readCapture.mockImplementation((id: string) => of(noteOf(id, '3', id === noteId(2) ? { archived: true } : {})));
                button().click();
                render();
                expect(api.createSession).not.toHaveBeenCalled();
                expect(removed.map(source => source.label)).toEqual(['Заметка 2']);
                expect(root().querySelector('.notice.error')?.textContent).toContain('Одна заметка уже в архиве');
                expect(button().textContent).toContain('Создать');
                authoring.readCapture.mockImplementation((id: string) => of(noteOf(id, '3', { deckId: '99999999-9999-4999-8999-999999999999' })));
                button().click();
                render();
                expect(removed).toHaveLength(3);
                expect(root().querySelector('.notice.error')?.textContent).toContain('Заметок из другой колоды: 2');
                expect(api.createSession).not.toHaveBeenCalled();
            });

            it('treats a note that cannot be found as gone, and a failed read as «try again», never as a created session', () => {
                create({ sources: [noteAt(1)] });
                const removed: ComposerSource[] = [];
                fixture.componentInstance.sourceRemoved.subscribe(source => removed.push(source));
                authoring.readCapture.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
                button().click();
                render();
                expect(removed).toHaveLength(1);
                expect(root().querySelector('.notice.error')?.textContent).toContain('не удалось найти');
                authoring.readCapture.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
                button().click();
                render();
                expect(removed).toHaveLength(1);
                expect(root().querySelector('.notice.error')?.textContent).toContain('Не удалось проверить заметки');
                expect(api.createSession).not.toHaveBeenCalled();
            });

            it('retries an unknown outcome without reading the notes again, so the same command and pins go out', () => {
                create({ sources: [noteAt(1), noteAt(2)] });
                authoring.readCapture.mockImplementation((id: string) => of(noteOf(id, '5')));
                api.createSession.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 }))).mockReturnValueOnce(of({ session: created, replayed: true }));
                button().click();
                render();
                expect(authoring.readCapture).toHaveBeenCalledTimes(2);
                authoring.readCapture.mockImplementation((id: string) => of(noteOf(id, '6')));
                button().click();
                expect(authoring.readCapture).toHaveBeenCalledTimes(2);
                expect(api.createSession.mock.calls[1]![2]).toBe(api.createSession.mock.calls[0]![2]);
                expect(JSON.stringify(api.createSession.mock.calls[1]![1])).toBe(JSON.stringify(api.createSession.mock.calls[0]![1]));
            });

            it('retries an unknown outcome with the same command while the notes are unchanged', () => {
                create({ sources: [noteAt(1), noteAt(2)] });
                api.createSession.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 }))).mockReturnValueOnce(of({ session: created, replayed: true }));
                button().click();
                render();
                button().click();
                expect(api.createSession.mock.calls[1]![2]).toBe(api.createSession.mock.calls[0]![2]);
            });

            it('keeps focus at the removed place: the chip now there, the last one when the last was removed, the request field when none is left', () => {
                create({ sources: [noteAt(1), noteAt(2), noteAt(3)] });
                document.body.append(root());
                const remove = (position: number) => root().querySelectorAll<HTMLButtonElement>('.chip-remove')[position]!.click();
                const focused = () => [...root().querySelectorAll('.chip-remove')].indexOf(document.activeElement as Element);
                remove(1);
                fixture.componentRef.setInput('sources', [noteAt(1), noteAt(3)]);
                render();
                expect(focused()).toBe(1);
                remove(1);
                fixture.componentRef.setInput('sources', [noteAt(1)]);
                render();
                expect(focused()).toBe(0);
                root().remove();
            });

            it('puts focus on the next chip after one is removed, and on the request field after the last', () => {
                create({ sources: [noteAt(1), noteAt(2)] });
                document.body.append(root());
                root().querySelector<HTMLButtonElement>('.chip-remove')!.click();
                fixture.componentRef.setInput('sources', [noteAt(2)]);
                render();
                fixture.whenStable();
                expect(document.activeElement).toBe(root().querySelector('.chip-remove'));
                root().querySelector<HTMLButtonElement>('.chip-remove')!.click();
                fixture.componentRef.setInput('sources', []);
                render();
                expect(document.activeElement).toBe(field());
                root().remove();
            });
        });
    });

    describe('voice input (AI-15)', () => {
        const speechOn = (): LearningCapabilities => capabilities({ speechToText: { available: true, reason: null } });

        it('puts the microphone next to the request field only while speechToText is available', () => {
            TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
            create({ caps: speechOn() });
            expect(root().querySelector('app-mic-button')).not.toBeNull();
            expect(root().querySelector('app-mic-button button')?.textContent).toContain('Начать запись');
            TestBed.resetTestingModule();
            create({ caps: capabilities({ speechToText: { available: false, reason: 'DISABLED' } }) });
            expect(root().querySelector('app-mic-button')).toBeNull();
        });
    });
});

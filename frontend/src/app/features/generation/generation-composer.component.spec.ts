import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { AuthService } from '../../auth.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from '../authoring/capabilities-api.service';
import { GenerationApiService } from './generation-api.service';
import { ComposerSource, ESTIMATE_DEBOUNCE_MS, GenerationComposerComponent, buildMaterialsSpec } from './generation-composer.component';
import { DEFAULT_SETTINGS } from './generation-settings.component';
import { GenerationEstimate, SessionDetail, parseEstimate, parseSessionDetail } from './generation.models';
import { NBSP } from './generation-view';
import { clone, examples, ids, problemResponse, usageContract } from './generation-test-data';

describe('GenerationComposerComponent', () => {
    let fixture: ComponentFixture<GenerationComposerComponent>;
    let api: SpyObj<GenerationApiService>;
    let user: ReturnType<typeof signal<{ displayName: string | null; profileUsername: string | null; email: string } | null>>;
    const created = parseSessionDetail(examples['sessionDetailCreated']);
    const affordable = (): GenerationEstimate => parseEstimate(usageContract['estimateResponse']);
    const short = (): GenerationEstimate => parseEstimate(usageContract['estimateResponseShortfall']);
    const capabilities = (overrides: Partial<LearningCapabilities> = {}): LearningCapabilities => ({
        ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null }, ...overrides });

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
        user = signal(options.name === null ? null : { displayName: options.name ?? 'Юзуру Мацуда', profileUsername: 'yuzuru', email: 'yuzuru@example.test' });
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api },
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
            expect(api.estimate.mock.calls[1]![1].settings.effort).toBe('SHORT');
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

    describe('creating the session', () => {
        it('sends the spec of the screen with a command id, never asks for a plan, and reports the session', () => {
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
    });
});

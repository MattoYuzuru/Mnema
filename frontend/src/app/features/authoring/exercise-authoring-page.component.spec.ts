import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { DEMO_ASSETS } from '../../content/exercise/demo/demo-media';
import { AuthoringBlock, ExerciseSpec, Mechanic } from '../../content/exercise/exercise-content.models';
import { parseExerciseSpec } from '../../content/exercise/exercise-content.parse';
import { catalogEntry } from '../../content/exercise/mechanic-catalog';
import { NativeDocument } from '../../content/native-document';
import { MEDIA_PLAYBACK_RESOLVER, MediaPlaybackResolver } from '../study/media-playback-resolver';
import { StudyApiService } from '../study/study-api.service';
import { AttemptFeedback } from '../study/study.models';
import { clone, fakePlayback, mechanics, removedNames } from '../study/study-test-data';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringProtocolError } from './authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from './capabilities-api.service';
import { ExerciseApiService } from './exercise-api.service';
import { ExerciseAuthoringPageComponent } from './exercise-authoring-page.component';
import { ExercisePreviewApiService } from './exercise-preview-api.service';
import { ExerciseDetail, ExercisePage } from './exercise.models';
import { ItemApiService } from './item-api.service';
import { NativeMediaUploadApi } from './native-media-upload.api';

describe('ExerciseAuthoringPageComponent', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const subject = mechanics['createSelfCheck'].exercise.subject as { memberKey: string; itemRevisionId: string };
    const deck: OwnDeck = {
        deckId: '11111111-1111-4111-8111-111111111111', revisionId: '22222222-2222-4222-8222-222222222222', rowVersion: '3',
        sequence: '3', metadata: { title: 'Память', description: '' }, visibility: 'private',
        createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', memberCount: 1, exerciseCount: 0
    };
    const paragraph = (nodeId: string, text: string) => ({ id: nodeId, type: 'paragraph', version: 1, attrs: {}, content: [
        { id: id(`7${nodeId.slice(-2)}`), type: 'text', version: 1, attrs: { text }, content: [] }] });
    const nativeDocument = { formatVersion: 1, root: { id: id('10'), type: 'doc', version: 1, attrs: {}, content: [
        paragraph('00000000-0000-4000-8000-000000000003', 'Ядро хранит ДНК'),
        paragraph('00000000-0000-4000-8000-000000000004', 'Memory'),
        paragraph('00000000-0000-4000-8000-000000000005', 'Attention')
    ] } } as unknown as NativeDocument;
    const item = {
        memberKey: subject.memberKey, itemRevisionId: subject.itemRevisionId, itemVersion: '0', ordinal: null, formatVersion: 1 as const,
        createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', deckId: deck.deckId,
        deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion, document: nativeDocument
    };
    const objective = { ...mechanics['exerciseDetail'].objective, memberKey: subject.memberKey };
    const emptyPage: ExercisePage = { deckId: deck.deckId, deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion,
        total: 0, exercises: [], nextCursor: null };
    const acknowledgement = { commandId: id('30'), deckId: deck.deckId, deckRevisionId: id('31'), deckVersion: '4',
        objectiveId: id('32'), objectiveKey: id('33'), objectiveRevisionId: id('34'), exerciseId: id('35'),
        exerciseRevisionId: id('36'), enabled: true };

    let api: jasmine.SpyObj<ExerciseApiService>;
    let previewApi: jasmine.SpyObj<ExercisePreviewApiService>;
    let studyApi: jasmine.SpyObj<StudyApiService>;
    let decksApi: jasmine.SpyObj<OwnDecksApiService>;
    let capabilityApi: jasmine.SpyObj<CapabilitiesApiService>;
    let router: jasmine.SpyObj<Router>;
    let fixture: ComponentFixture<ExerciseAuthoringPageComponent>;
    let getUserMedia: jasmine.Spy;
    let scroll: jasmine.Spy;
    let savedQuery = '';

    function detailOf(spec: ExerciseSpec, ordinal = 0): ExerciseDetail {
        return { exerciseId: id('50'), exerciseRevisionId: id('51'), exerciseVersion: '1', ordinal,
            createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', objective,
            deckId: deck.deckId, deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion, ...spec };
    }

    function summaryOf(detail: ExerciseDetail, exerciseId = detail.exerciseId) {
        return { exerciseId, exerciseRevisionId: detail.exerciseRevisionId, exerciseVersion: '1', ordinal: detail.ordinal,
            type: detail.type, enabled: detail.enabled, schemaVersion: 2 as const, createdAt: detail.createdAt,
            updatedAt: detail.updatedAt, objective: detail.objective };
    }

    /** `detail` opens the edit route; `others` are further exercises of the material already listed. */
    function configure(detail: ExerciseDetail | null = null, capabilities: LearningCapabilities | 'error' = CAPABILITIES_UNAVAILABLE,
                       others: readonly ExerciseDetail[] = []): void {
        decksApi = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        const items = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['read']);
        api = jasmine.createSpyObj<ExerciseApiService>('ExerciseApiService', ['list', 'read', 'create', 'update', 'delete']);
        capabilityApi = jasmine.createSpyObj<CapabilitiesApiService>('CapabilitiesApiService', ['read']);
        previewApi = jasmine.createSpyObj<ExercisePreviewApiService>('ExercisePreviewApiService', ['submit', 'checkPair', 'hint']);
        studyApi = jasmine.createSpyObj<StudyApiService>('StudyApiService', ['start', 'read', 'refill', 'submit', 'checkPair', 'hint', 'revealTranscript']);
        router = jasmine.createSpyObj<Router>('Router', ['navigate']);
        decksApi.detail.and.returnValue(of(deck)); items.read.and.returnValue(of(item));
        const listed = [...(detail === null ? [] : [summaryOf(detail)]), ...others.map(other => summaryOf(other))];
        api.list.and.returnValue(of({ ...emptyPage, total: listed.length, exercises: listed }));
        api.read.and.callFake((_deckId, exerciseId) => of(exerciseId === id('50') && detail !== null ? detail
            : others.find(other => other.exerciseId === exerciseId) ?? detailOf(mechanics['createSelfCheck'].exercise)));
        api.create.and.returnValue(of({ acknowledgement, replayed: false }));
        api.update.and.returnValue(of({ acknowledgement, replayed: false }));
        api.delete.and.returnValue(of(undefined));
        capabilityApi.read.and.returnValue(capabilities === 'error' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(capabilities));
        router.navigate.and.resolveTo(true);
        previewApi.submit.and.returnValue(of({ result: 'CORRECT', appliedRules: [] } as AttemptFeedback));
        previewApi.checkPair.and.returnValue(of(true));
        previewApi.hint.and.returnValue(of('m'));
        const playback = jasmine.createSpyObj<MediaPlaybackResolver>('MediaPlaybackResolver', ['resolve']);
        playback.resolve.and.callFake(fakePlayback);
        const upload = jasmine.createSpyObj<NativeMediaUploadApi>('NativeMediaUploadApi',
            ['policy', 'intent', 'status', 'partUrls', 'completedParts', 'finalize', 'retry', 'cancel', 'renewSingle', 'put']);
        getUserMedia = spyOn(navigator.mediaDevices, 'getUserMedia').and.callFake(() =>
            Promise.reject(new DOMException('denied', 'NotAllowedError')));
        scroll = spyOn(HTMLElement.prototype, 'scrollIntoView');
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: {
                paramMap: convertToParamMap({ deckId: deck.deckId, ...(detail ? { exerciseId: detail.exerciseId } : { memberKey: item.memberKey }) }),
                queryParamMap: convertToParamMap(savedQuery === '' ? {} : { saved: savedQuery })
            } } },
            { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decksApi },
            { provide: ItemApiService, useValue: items }, { provide: ExerciseApiService, useValue: api },
            { provide: ExercisePreviewApiService, useValue: previewApi }, { provide: StudyApiService, useValue: studyApi },
            { provide: CapabilitiesApiService, useValue: capabilityApi }, { provide: MEDIA_PLAYBACK_RESOLVER, useValue: playback },
            { provide: NativeMediaUploadApi, useValue: upload }
        ] });
        fixture = TestBed.createComponent(ExerciseAuthoringPageComponent);
        fixture.detectChanges();
    }

    beforeEach(() => { savedQuery = ''; });

    const page = () => fixture.nativeElement as HTMLElement;
    const component = () => fixture.componentInstance;
    const text = (value: string): AuthoringBlock => ({ kind: 'TEXT', text: value });
    const refresh = () => { fixture.detectChanges(); fixture.detectChanges(); };

    /** Picks a tile like an author would, then (optionally) opens every step as a completed flow would. */
    function select(type: Mechanic, openAll = true): void {
        component().choose({ mechanic: type, deliberate: false }); refresh();
        if (component().pendingSwitch() !== null) { component().confirmSwitch(); refresh(); }
        if (openAll) { component().opened.update(current => ({ ...current, [type]: catalogEntry(type).steps.length })); refresh(); }
    }

    function buttonByText(label: string, root: ParentNode = page()): HTMLButtonElement {
        const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === label);
        if (button === undefined) throw new Error(`Missing button ${label}`);
        return button;
    }

    function created(): { objective: Record<string, unknown>; exercise: ExerciseSpec } {
        const args = api.create.calls.mostRecent().args;
        return { objective: args[3] as unknown as Record<string, unknown>, exercise: args[4] };
    }

    const radio = (type: Mechanic) => page().querySelector<HTMLInputElement>(`input[name="mechanic"][value="${type}"]`)!;
    const preview = () => page().querySelector<HTMLElement>('app-exercise-preview-host');
    const mode = () => preview()?.querySelector('.preview')?.getAttribute('data-mode') ?? null;
    const stepIds = () => [...page().querySelectorAll('section.step')].map(step => step.id);

    function moveOn(): void { buttonByText('Продолжить').click(); refresh(); }

    function type(selector: string, value: string): void {
        const field = page().querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
        field.value = value; field.dispatchEvent(new Event('input')); refresh();
    }

    describe('initial states', () => {
        it('a new exercise shows the type choice alone: no preview, no step, no list, no side column, no material block', () => {
            configure();
            expect(getUserMedia).not.toHaveBeenCalled();
            expect(page().querySelector('app-mechanic-picker legend')?.textContent).toBe('1. Тип упражнения');
            const names = [...page().querySelectorAll('.tile-title')].map(node => node.textContent);
            expect(names).toEqual(['Вспомнить и сверить', 'Ввести ответ', 'Заполнить пропуски', 'Выбрать ответ', 'Сопоставить элементы']);
            expect([...page().querySelectorAll('input[name="mechanic"]')].some(input => (input as HTMLInputElement).checked)).toBeFalse();
            expect(component().mechanic()).toBeNull();
            expect(preview()).toBeNull();
            expect(stepIds()).toEqual([]);
            expect(page().querySelector('.existing, #existing-exercises, .jump-link, aside, .preview-column, .material-card')).toBeNull();
            expect(page().textContent).not.toContain('Актуальный материал');
            expect(page().querySelector('app-native-media-surface')).toBeNull();
            for (const legacy of removedNames) expect(page().innerHTML).not.toContain(legacy);
            expect(page().querySelector('[data-continue]')).toBeNull();
            expect(page().querySelector('button[type="submit"]')).toBeNull();
        });

        it('uses the catalog texts for the tiles and makes the whole tile a native radio inside a label', () => {
            configure();
            const tiles = [...page().querySelectorAll('label.tile')];
            expect(tiles.length).toBe(5);
            expect(tiles[1].textContent).toContain('Ученик напишет ответ на ваш вопрос.');
            for (const tile of tiles) expect(tile.querySelector('input[type="radio"][name="mechanic"]')).not.toBeNull();
            expect(page().textContent).not.toContain('ИИ');
        });

        it('a material with exercises shows the builder on top and the list below on the same page, with a small anchor', () => {
            const detail = detailOf(mechanics['createChoiceVideoMultiple'].exercise);
            configure(null, CAPABILITIES_UNAVAILABLE, [detail]);
            expect(page().querySelector('app-mechanic-picker')).not.toBeNull();
            const form = page().querySelector('form.inspector')!;
            const list = page().querySelector('#existing-exercises')!;
            expect(form.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(list.querySelector('h2')?.textContent).toContain('Упражнения этого материала · 1');
            expect(page().querySelector('.jump-link')?.textContent?.trim()).toBe('К упражнениям · 1');
            // No intermediate create/view screen: the tiles are right there.
            expect(page().querySelectorAll('label.tile').length).toBe(5);
            const row = list.querySelector('li')!;
            expect(row.textContent).toContain('Выбрать ответ');
            expect(row.textContent).toContain('Признаки реакции в опыте');
            expect(row.querySelector('input[type="checkbox"]')).not.toBeNull();
            expect(row.querySelector('a.button')?.textContent?.trim()).toBe('Настроить');
        });

        it('the anchor jumps to the list without leaving the page', () => {
            configure(null, CAPABILITIES_UNAVAILABLE, [detailOf(mechanics['createChoiceVideoMultiple'].exercise)]);
            const link = page().querySelector<HTMLAnchorElement>('.jump-link')!;
            const event = new MouseEvent('click', { bubbles: true, cancelable: true });
            link.dispatchEvent(event);
            expect(event.defaultPrevented).toBeTrue();
            expect(scroll).toHaveBeenCalledTimes(1);
            expect(router.navigate).not.toHaveBeenCalled();
        });
    });

    describe('tile activation, preview and scrolling', () => {
        it('a click on a tile reveals the preview and the first step and scrolls to the preview exactly once', () => {
            configure();
            const label = radio('CHOICE').closest('label')!;
            label.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            radio('CHOICE').click(); refresh();
            expect(component().mechanic()).toBe('CHOICE');
            expect(preview()).not.toBeNull();
            expect(stepIds()).toEqual(['step-prompt']);
            expect(page().querySelector('#step-prompt h2')?.textContent?.trim()).toBe('2. Вопрос');
            expect(scroll).toHaveBeenCalledTimes(1);
            expect((scroll.calls.mostRecent().object as HTMLElement).id).toBe('exercise-preview-anchor');
            expect(scroll.calls.mostRecent().args[0]).toEqual({ block: 'start', behavior: 'smooth' });
            // Switching to the next tile on an empty form changes the example and scrolls again, once.
            radio('MATCH').closest('label')!.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            radio('MATCH').click(); refresh();
            expect(scroll).toHaveBeenCalledTimes(2);
            expect(component().pendingSwitch()).toBeNull();
        });

        it('arrow keys in the group select a tile without scrolling or moving focus', () => {
            configure();
            const first = radio('SELF_CHECK');
            first.focus();
            first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
            first.click(); refresh();
            expect(component().mechanic()).toBe('SELF_CHECK');
            expect(preview()).not.toBeNull();
            expect(scroll).not.toHaveBeenCalled();
            const next = radio('FREE_RESPONSE');
            next.focus();
            next.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
            next.click(); refresh();
            expect(component().mechanic()).toBe('FREE_RESPONSE');
            expect(scroll).not.toHaveBeenCalled();
            expect(document.activeElement).toBe(next);
        });

        it('activating the tile that is already chosen takes the author back to the example', () => {
            configure();
            select('SELF_CHECK', false);
            scroll.calls.reset();
            radio('SELF_CHECK').click();
            expect(scroll).toHaveBeenCalledTimes(1);
            expect(component().mechanic()).toBe('SELF_CHECK');
        });

        it('respects reduced motion: the jump is instant', () => {
            configure();
            const original = window.matchMedia.bind(window);
            spyOn(window, 'matchMedia').and.callFake((value: string) => value.includes('prefers-reduced-motion')
                ? { matches: true, media: value } as MediaQueryList : original(value));
            component().choose({ mechanic: 'CLOZE', deliberate: true }); refresh();
            expect(scroll.calls.mostRecent().args[0]).toEqual({ block: 'start', behavior: 'auto' });
        });

        it('never scrolls or moves focus while the author types, composes or uploads', () => {
            configure();
            select('FREE_RESPONSE', false);
            scroll.calls.reset();
            const area = page().querySelector<HTMLTextAreaElement>('#free-response-prompt-text-0')!;
            area.focus();
            area.dispatchEvent(new CompositionEvent('compositionstart'));
            for (const value of ['К', 'Ка', 'Как']) type('#free-response-prompt-text-0', value);
            area.dispatchEvent(new CompositionEvent('compositionend'));
            area.dispatchEvent(new Event('blur'));
            expect(scroll).not.toHaveBeenCalled();
            expect(document.activeElement).toBe(area);
            expect(stepIds()).toEqual(['step-prompt']);
        });

        it('opens the next step only on an explicit «Продолжить», brings it into view and keeps the focus on its heading', () => {
            configure();
            select('FREE_RESPONSE', false);
            scroll.calls.reset();
            buttonByText('Продолжить').click(); refresh();
            expect(stepIds()).toEqual(['step-prompt']);
            expect(page().querySelector('#step-prompt .step-problem')?.textContent).toContain('Чтобы продолжить, исправьте');
            expect(scroll).not.toHaveBeenCalled();

            type('#free-response-prompt-text-0', 'Столица Франции?');
            expect(page().querySelector('#step-prompt .step-problem')).toBeNull();
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-answers']);
            expect(scroll).toHaveBeenCalledTimes(1);
            expect((scroll.calls.mostRecent().object as HTMLElement).id).toBe('step-answers');
            expect(document.activeElement?.id).toBe('step-answers-title');
            expect(page().querySelector('#step-answers h2')?.textContent?.trim()).toBe('3. Допустимые ответы');
            expect(page().querySelectorAll('[data-continue]').length).toBe(1);

            // Settings of an open step update the preview but never jump to it.
            scroll.calls.reset();
            type('#free-response-answer-' + component().drafts().FREE_RESPONSE.answer.rows[0].id, 'Париж');
            expect(scroll).not.toHaveBeenCalled();
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-answers', 'step-finish']);
            expect(page().querySelector('#step-finish h2')?.textContent?.trim()).toBe('4. Название и сохранение');
            expect(page().querySelector('[data-continue]')).toBeNull();
            expect(page().querySelector('.save-bar button[type="submit"]')?.textContent).toContain('Создать упражнение');
        });

        it('follows every mechanic through its own ordered steps and numbers them consecutively', () => {
            const expected: Record<Mechanic, string[]> = {
                SELF_CHECK: ['2. Условие', '3. Эталон ответа', '4. Название и сохранение'],
                FREE_RESPONSE: ['2. Вопрос', '3. Допустимые ответы', '4. Название и сохранение'],
                CLOZE: ['2. Контекст', '3. Текст и пропуски', '4. Название и сохранение'],
                CHOICE: ['2. Вопрос', '3. Варианты ответа', '4. Название и сохранение'],
                MATCH: ['2. Общая инструкция', '3. Пары', '4. Название и сохранение']
            };
            configure();
            for (const mechanic of Object.keys(expected) as Mechanic[]) {
                select(mechanic);
                expect([...page().querySelectorAll('.step-title')].map(node => node.textContent?.trim())).withContext(mechanic).toEqual(expected[mechanic]);
            }
        });

        it('optional steps pass without input while required ones name the problem', () => {
            configure();
            select('CLOZE', false);
            expect(page().querySelector('#step-context h2')?.textContent).toContain('Контекст');
            moveOn();
            expect(stepIds()).toEqual(['step-context', 'step-passage']);
            moveOn();
            expect(stepIds()).toEqual(['step-context', 'step-passage']);
            expect(page().querySelector('#step-passage .step-problem')?.textContent).toContain('пропусков');
            select('MATCH', false);
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-pairs']);
            moveOn();
            expect(page().querySelector('#step-pairs')?.textContent).toContain('Блок 1');
        });
    });

    describe('preview modes', () => {
        it('shows a playable demo with a visible badge and the explanation while nothing is filled in', () => {
            configure();
            for (const mechanic of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH'] as const) {
                select(mechanic, false);
                expect(mode()).withContext(mechanic).toBe('DEMO');
                expect(preview()!.querySelector('.badge')?.textContent?.trim()).toBe('Пример');
                expect(preview()!.querySelector('[data-preview-caption]')?.textContent)
                    .toContain('Это пример упражнения. Заполните шаги ниже — здесь появится ваше задание');
                expect(preview()!.querySelector('app-learner-exercise')).not.toBeNull();
            }
            // Selecting tiles only explores: nothing was authored, so leaving the page is free.
            expect(component().dirty()).toBeFalse();
            expect(component().canLeave()).toBeTrue();
            expect(studyApi.start).not.toHaveBeenCalled();
        });

        it('lets the author play the demo through the preview endpoint only and restart it without touching the form', () => {
            configure();
            select('CHOICE', false);
            preview()!.querySelector<HTMLInputElement>('input[type="radio"]')!.click(); refresh();
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click(); refresh();
            expect(previewApi.submit).toHaveBeenCalledTimes(1);
            const [exercise, submission] = previewApi.submit.calls.mostRecent().args;
            expect(exercise.type).toBe('CHOICE');
            expect(submission.response.kind).toBe('CHOICE');
            expect(preview()!.querySelector('#preview-result-title')?.textContent).toBe('Верно');
            expect(preview()!.textContent).toContain('Пример завершён');
            const before = JSON.stringify(component().drafts());
            buttonByText('Начать заново', preview()!).click(); refresh();
            expect(preview()!.querySelector('app-learner-exercise')).not.toBeNull();
            expect(preview()!.querySelector('#preview-result-title')).toBeNull();
            expect(JSON.stringify(component().drafts())).toBe(before);
            expect(component().dirty()).toBeFalse();
            expect(studyApi.submit).not.toHaveBeenCalled();
            expect(studyApi.checkPair).not.toHaveBeenCalled();
            expect(studyApi.hint).not.toHaveBeenCalled();
            expect(api.create).not.toHaveBeenCalled();
            expect(api.update).not.toHaveBeenCalled();
        });

        it('switching tiles on an empty form changes the example and never copies demo content into the form', () => {
            configure();
            select('SELF_CHECK', false);
            const demoText = preview()!.textContent;
            select('MATCH', false);
            expect(mode()).toBe('DEMO');
            expect(preview()!.textContent).not.toBe(demoText);
            for (const kind of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH'] as const) {
                expect(JSON.stringify(component().drafts()[kind])).not.toContain('de000000');
                expect(JSON.stringify(component().drafts()[kind])).not.toContain('Канберра');
            }
            expect(component().stepErrors()).toEqual({});
        });

        it('the first authored input replaces the demo with the author\'s own draft and placeholders, never mixing them', () => {
            configure();
            select('CHOICE', false);
            expect(mode()).toBe('DEMO');
            expect(preview()!.textContent).toContain('Звук 1');
            type('#choice-prompt-text-0', 'Столица Японии?');
            expect(mode()).toBe('AUTHOR_DRAFT');
            const surface = preview()!.textContent ?? '';
            expect(surface).toContain('Столица Японии?');
            expect(surface).toContain('Добавьте вариант');
            expect(surface).not.toContain('Звук 1');
            expect(preview()!.querySelector('.badge')?.textContent?.trim()).toBe('Ваше задание');
            expect(preview()!.querySelector('app-learner-media')).toBeNull();
            const submit = preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!;
            expect(submit.disabled).toBeTrue();
            expect(preview()!.querySelector('.blocked')?.textContent).toContain('Проверить ответ пока нельзя');
            expect(previewApi.submit).not.toHaveBeenCalled();
        });

        it('shows the neutral placeholders for every unfinished part', () => {
            configure();
            select('SELF_CHECK', false);
            type('#self-check-prompt-text-0', 'Что такое ДНК?');
            expect(preview()!.textContent).toContain('Что такое ДНК?');
            preview()!.querySelector<HTMLButtonElement>('[data-answer-control]')!.click(); refresh();
            expect(preview()!.textContent).toContain('Укажите правильный ответ');
        });

        it('a complete draft becomes playable and is evaluated through the preview endpoint with the author\'s own key', () => {
            configure();
            select('FREE_RESPONSE');
            type('#free-response-prompt-text-0', 'Столица Франции?');
            type('#free-response-answer-' + component().drafts().FREE_RESPONSE.answer.rows[0].id, 'Париж');
            expect(mode()).toBe('AUTHOR_READY');
            expect(preview()!.querySelector('.blocked')).toBeNull();
            const answer = preview()!.querySelector<HTMLTextAreaElement>('textarea')!;
            answer.value = 'париж'; answer.dispatchEvent(new Event('input')); refresh();
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click(); refresh();
            const [exercise, submission] = previewApi.submit.calls.mostRecent().args;
            expect(JSON.stringify(exercise)).toContain('Париж');
            expect(JSON.stringify(exercise)).not.toContain('de000000');
            expect(exercise).not.toEqual(jasmine.objectContaining({ subject: jasmine.anything() }));
            expect(submission).toEqual({ response: { kind: 'TEXT', text: 'париж' }, hintedBlankIds: [], pairMistakes: false, transcriptRevealed: false });
            expect(preview()!.querySelector('#preview-result-title')?.textContent).toBe('Верно');
            expect(preview()!.textContent).toContain('Проба завершена');
            expect(api.create).not.toHaveBeenCalled();
        });

        it('any change of the form discards the old trial, including the feedback of an answered attempt', () => {
            configure();
            select('FREE_RESPONSE');
            type('#free-response-prompt-text-0', 'Столица Франции?');
            type('#free-response-answer-' + component().drafts().FREE_RESPONSE.answer.rows[0].id, 'Париж');
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click(); refresh();
            expect(preview()!.querySelector('#preview-result-title')).not.toBeNull();
            type('#free-response-prompt-text-0', 'Столица Германии?');
            expect(preview()!.querySelector('#preview-result-title')).toBeNull();
            expect(preview()!.textContent).toContain('Столица Германии?');
            expect(component().drafts().FREE_RESPONSE.prompt).toEqual([text('Столица Германии?')]);
        });

        it('drops a late result for an outdated trial instead of showing it', () => {
            configure();
            select('FREE_RESPONSE');
            type('#free-response-prompt-text-0', 'Вопрос');
            type('#free-response-answer-' + component().drafts().FREE_RESPONSE.answer.rows[0].id, 'ответ');
            const late = new Subject<AttemptFeedback>();
            previewApi.submit.and.returnValue(late);
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click(); refresh();
            type('#free-response-prompt-text-0', 'Вопрос изменён');
            late.next({ result: 'CORRECT', appliedRules: [] }); refresh();
            expect(preview()!.querySelector('#preview-result-title')).toBeNull();
            expect(preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.disabled).toBeFalse();
        });

        it('opening an existing exercise shows its own content in the preview, never the demo', () => {
            const detail = detailOf(mechanics['createSelfCheck'].exercise);
            configure(detail);
            expect(mode()).toBe('AUTHOR_READY');
            expect(preview()!.querySelector('.badge')?.textContent?.trim()).toBe('Ваше задание');
            expect(preview()!.textContent).not.toContain('Канберра');
            expect(preview()!.textContent).toContain('Назовите органеллы');
            // Even after clearing everything an existing exercise falls back to a draft, not to the example.
            component().setPrompt([text('')]); component().setReference([text('')]); refresh();
            expect(mode()).toBe('AUTHOR_DRAFT');
        });

        it('shows the voice input and the semantic AI check as unavailable with a reason and never inside the demo', () => {
            configure(null, { aiAssessment: { available: false, reason: 'DISABLED' }, speechToText: { available: false, reason: 'DISABLED' } });
            select('FREE_RESPONSE');
            const voice = preview()!.querySelector<HTMLButtonElement>('.voice button')!;
            expect(voice.disabled).toBeTrue();
            expect(preview()!.querySelector('.voice .hint')?.textContent).toContain('Голосовой ответ пока недоступен');
            const toggle = page().querySelector<HTMLInputElement>('input[role="switch"]')!;
            expect(toggle.disabled).toBeTrue();
            expect(preview()!.textContent).not.toContain('ИИ');
        });

        it('keeps demo assets out of every request: the demo plays from the bundle, the preview endpoint gets ids only on submit', () => {
            configure();
            select('CHOICE', false);
            expect(preview()!.querySelectorAll('app-learner-media').length).toBe(3);
            expect(previewApi.submit).not.toHaveBeenCalled();
            expect(api.create).not.toHaveBeenCalled();
            expect(JSON.stringify(component().drafts())).not.toContain(DEMO_ASSETS.toneLow);
        });
    });

    describe('switching the mechanic', () => {
        it('switches instantly when the draft has nothing the other type would lose, carrying the question along', () => {
            configure();
            select('CHOICE', false);
            type('#choice-prompt-text-0', 'Что изображено?');
            select('FREE_RESPONSE', false);
            expect(component().pendingSwitch()).toBeNull();
            expect(component().mechanic()).toBe('FREE_RESPONSE');
            expect(component().drafts().FREE_RESPONSE.prompt).toEqual([text('Что изображено?')]);
            expect(component().drafts().CHOICE.prompt).toEqual([text('Что изображено?')]);
            expect(page().querySelector<HTMLTextAreaElement>('#free-response-prompt-text-0')?.value).toBe('Что изображено?');
        });

        it('asks inline before hiding mechanic-specific data and keeps everything on cancel', () => {
            configure();
            select('CHOICE');
            const spec = mechanics['createChoiceVideoMultiple'].exercise;
            component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
                correctIds: spec.answerKey.correctOptionIds }); refresh();
            const confirm = spyOn(window, 'confirm');
            scroll.calls.reset();
            radio('MATCH').click(); refresh();
            expect(confirm).not.toHaveBeenCalled();
            const dialog = page().querySelector('.switch-confirm')!;
            expect(dialog.getAttribute('role')).toBe('alertdialog');
            expect(dialog.textContent).toContain('Переключиться на «Сопоставить элементы»?');
            expect(dialog.textContent).toContain('варианты ответа и отметки правильных');
            expect(component().mechanic()).toBe('CHOICE');
            expect(radio('MATCH').checked).toBeTrue();
            expect(scroll).not.toHaveBeenCalled();

            buttonByText('Отмена', dialog).click(); refresh();
            expect(page().querySelector('.switch-confirm')).toBeNull();
            expect(component().mechanic()).toBe('CHOICE');
            expect(radio('CHOICE').checked).toBeTrue();
            expect(radio('MATCH').checked).toBeFalse();
            expect(component().drafts().CHOICE.correctIds).toEqual(spec.answerKey.correctOptionIds);
            expect(component().drafts().CHOICE.selectionMode).toBe('MULTIPLE');
            expect(document.activeElement).toBe(radio('CHOICE'));
        });

        it('keeps the old draft in memory when the author confirms, and asks again on the way back', () => {
            configure();
            select('CHOICE');
            const spec = mechanics['createChoiceVideoMultiple'].exercise;
            component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
                correctIds: spec.answerKey.correctOptionIds }); refresh();
            scroll.calls.reset();
            radio('MATCH').click(); refresh();
            buttonByText('Переключиться').click(); refresh();
            expect(component().mechanic()).toBe('MATCH');
            expect(component().pendingSwitch()).toBeNull();
            expect(scroll).toHaveBeenCalledTimes(1);
            expect(component().drafts().CHOICE.options).toEqual(spec.content.options);
            // The hidden data is not saved with the new type: only the carried question and an empty pair list exist.
            component().opened.update(current => ({ ...current, MATCH: 3 })); refresh();
            component().save();
            expect(api.create).not.toHaveBeenCalled();
            expect(component().mechanic()).toBe('MATCH');
        });

        it('preserves what was typed for each mechanic when switching between them', () => {
            configure();
            select('FREE_RESPONSE');
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Что такое память?')],
                answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'x' }] } });
            select('CLOZE');
            component().setCloze({ ...component().drafts().CLOZE, texts: ['Память — это '] });
            select('CHOICE');
            component().setChoice({ ...component().drafts().CHOICE, prompt: [text('Выберите термин')] });
            select('MATCH');
            select('FREE_RESPONSE');
            expect(page().querySelector<HTMLTextAreaElement>('#free-response-prompt-text-0')?.value).toBe('Что такое память?');
            select('CLOZE');
            expect(page().querySelector<HTMLTextAreaElement>('#cloze-text-0')?.value).toBe('Память — это ');
            select('CHOICE');
            expect(component().drafts().CHOICE.prompt).toEqual([text('Выберите термин')]);
            expect(component().dirty()).toBeTrue();
        });
    });

    describe('creates every mechanic', () => {
        it('SELF_CHECK with an image question and a rich reference', () => {
            configure();
            select('SELF_CHECK');
            const fixtureSpec = mechanics['createSelfCheck'].exercise;
            component().setPrompt(fixtureSpec.content.prompt);
            component().setReference(fixtureSpec.content.reference);
            component().save();
            const { objective: command, exercise } = created();
            expect(exercise).toEqual(fixtureSpec);
            expect(command).toEqual({ operation: 'create', title: 'Назовите органеллы 1–3 и их функции.' });
            expect(router.navigate).toHaveBeenCalled();
            expect(component().dirty()).toBeFalse();
        });

        it('FREE_RESPONSE with audio, alternatives and explicit normalization', () => {
            configure();
            const spec = mechanics['createFreeResponseAudio'].exercise;
            select('FREE_RESPONSE');
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: spec.content.prompt, reference: [],
                answer: { rows: spec.answerKey.accepted.map((value: string, index: number) => ({ id: `row-${index}`, value })),
                    normalization: spec.answerKey.normalization, matchingMode: 'STRICT' } });
            component().setObjectiveTitle('Запись слова на слух');
            component().save();
            expect(created().exercise).toEqual(spec);
            expect(created().objective).toEqual({ operation: 'create', title: 'Запись слова на слух' });
        });

        it('CLOZE with three blanks, repeated words and code line breaks kept verbatim', () => {
            configure(detailOf(mechanics['createCloze'].exercise));
            component().save();
            expect(api.update.calls.mostRecent().args[5] as unknown).toEqual({ operation: 'reuse',
                objectiveId: objective.objectiveId, objectiveRevisionId: objective.objectiveRevisionId });
            expect(api.update.calls.mostRecent().args[6]).toEqual(mechanics['createCloze'].exercise);
        });

        it('CHOICE multiple with video in the question and mixed-media options', () => {
            configure();
            const spec = mechanics['createChoiceVideoMultiple'].exercise;
            select('CHOICE');
            component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
                correctIds: spec.answerKey.correctOptionIds });
            component().save();
            expect(created().exercise).toEqual(spec);
        });

        it('MATCH with text, audio, image, video and material items on either side', () => {
            configure();
            const spec = mechanics['createMatchMixed'].exercise;
            select('MATCH');
            component().setMatch({ prompt: spec.content.prompt, pairs: spec.content.left.map((left: { itemId: string; blocks: AuthoringBlock[] }, index: number) => ({
                pairId: `pair-${index}`, left, right: spec.content.right[index] })) });
            component().save();
            expect(created().exercise).toEqual(spec);
            expect(parseExerciseSpec(created().exercise)).toEqual(spec);
        });

        it('never saves the demo: a pristine form is refused and no demo id reaches a saved payload', () => {
            configure();
            select('CHOICE');
            expect(mode()).toBe('DEMO');
            component().save(); refresh();
            expect(api.create).not.toHaveBeenCalled();
            expect(component().phase()).toBe('rejected');
            expect(page().querySelector('#exercise-errors')?.textContent).toContain('Введите текст блока');
            const spec = mechanics['createChoiceVideoMultiple'].exercise;
            component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
                correctIds: spec.answerKey.correctOptionIds });
            component().save();
            expect(JSON.stringify(created().exercise)).not.toContain('de000000');
            expect(JSON.stringify(created().exercise)).not.toContain('Звук 1');
        });
    });

    describe('reopens and saves every mechanic without changing it', () => {
        const cases: Array<[Mechanic, string]> = [['SELF_CHECK', 'createSelfCheck'], ['FREE_RESPONSE', 'createFreeResponseAudio'],
            ['CLOZE', 'createCloze'], ['CHOICE', 'createChoiceVideoMultiple'], ['MATCH', 'createMatchMixed']];
        for (const [kind, name] of cases) {
            it(`${kind}`, () => {
                const spec: ExerciseSpec = mechanics[name].exercise;
                configure(detailOf(spec));
                expect(component().mechanic()).toBe(kind);
                expect(component().objectiveMode()).toBe('reuse');
                // Every relevant step is already open and filled in; nothing asks to be filled again.
                expect(stepIds().length).toBe(3);
                expect(page().querySelector('[data-continue]')).toBeNull();
                expect(radio(kind).checked).toBeTrue();
                expect(mode()).toBe('AUTHOR_READY');
                component().save();
                expect(api.update.calls.mostRecent().args[6]).toEqual(spec);
                expect(api.update.calls.mostRecent().args[4]).toBe(id('51'));
            });
        }
    });

    it('keeps correct marks when switching multiple to single and reports a fix-required error', () => {
        configure();
        select('CHOICE');
        const spec = mechanics['createChoiceVideoMultiple'].exercise;
        component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
            correctIds: spec.answerKey.correctOptionIds });
        refresh();
        expect(page().querySelector('[id$="selection-error"]')).toBeNull();

        page().querySelector<HTMLInputElement>('app-choice-editor input[type="radio"]:not(:checked)')!.click();
        refresh();
        expect(component().drafts().CHOICE.selectionMode).toBe('SINGLE');
        expect(component().drafts().CHOICE.correctIds).toEqual(spec.answerKey.correctOptionIds);
        const error = page().querySelector('[id$="selection-error"]');
        expect(error?.textContent).toContain('только один вариант');
        expect(error?.getAttribute('role')).toBe('alert');

        component().save(); refresh();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['selection']).toContain('Снимите лишние отметки');
        expect(component().phase()).toBe('rejected');

        // Unchecking one mark fixes it without losing the other one.
        page().querySelectorAll<HTMLInputElement>('app-choice-editor .card > .check-line input[type="checkbox"]')[0].click(); refresh();
        expect(component().drafts().CHOICE.correctIds).toEqual([spec.answerKey.correctOptionIds[1]]);
        expect(page().querySelector('[id$="selection-error"]')).toBeNull();
    });

    it('marks, adds, reorders and removes choice options within 2 to 12', () => {
        configure();
        select('CHOICE');
        const root = page();
        expect(root.querySelectorAll('app-choice-editor [data-option]').length).toBe(2);
        expect(root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Удалить вариант 1"]')?.disabled).toBeTrue();
        for (let index = 0; index < 10; index++) root.querySelector<HTMLButtonElement>('[data-add-option]')!.click();
        refresh();
        expect(root.querySelectorAll('app-choice-editor [data-option]').length).toBe(12);
        expect(root.querySelector<HTMLButtonElement>('[data-add-option]')?.disabled).toBeTrue();
        const first = component().drafts().CHOICE.options[0].optionId;
        root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Опустить вариант 1"]')!.click(); refresh();
        expect(component().drafts().CHOICE.options[1].optionId).toBe(first);
        root.querySelector<HTMLInputElement>('app-choice-editor .card > .check-line input[type="checkbox"]')!.click(); refresh();
        expect(component().drafts().CHOICE.correctIds.length).toBe(1);
        root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Удалить вариант 1"]')!.click(); refresh();
        expect(component().drafts().CHOICE.options.length).toBe(11);
        expect(component().drafts().CHOICE.correctIds.length).toBe(0);
    });

    describe('cloze passage editing', () => {
        function selectIn(index: number, start: number, end: number): void {
            const area = page().querySelector<HTMLTextAreaElement>(`#cloze-text-${index}`)!;
            area.focus(); area.setSelectionRange(start, end);
            area.dispatchEvent(new Event('select')); refresh();
        }
        function typeIn(index: number, value: string): void { type(`#cloze-text-${index}`, value); }

        it('adds, edits and deletes blanks; repeated words get their own blank ids and nothing is lost', () => {
            configure();
            select('CLOZE');
            typeIn(0, 'map and map\n    .toList()');
            selectIn(0, 8, 11);
            buttonByText('Сделать пропуском').click(); refresh();
            let cloze = component().drafts().CLOZE;
            expect(cloze.texts).toEqual(['map and ', '\n    .toList()']);
            expect(cloze.blanks.length).toBe(1);
            expect(cloze.blanks[0].answer.rows[0].value).toBe('map');
            // The preview shows the passage as soon as it has a blank.
            expect(preview()!.querySelectorAll('app-cloze-passage input').length).toBe(1);

            selectIn(0, 0, 3);
            buttonByText('Сделать пропуском').click(); refresh();
            cloze = component().drafts().CLOZE;
            expect(cloze.texts).toEqual(['', ' and ', '\n    .toList()']);
            expect(cloze.blanks.map(blank => blank.answer.rows[0].value)).toEqual(['map', 'map']);
            expect(new Set(cloze.blanks.map(blank => blank.blankId)).size).toBe(2);

            const cards = page().querySelectorAll('[data-blank]');
            expect(cards.length).toBe(2);
            const length = cards[1].querySelector<HTMLInputElement>('input[type="number"]')!;
            length.value = '8'; length.dispatchEvent(new Event('input'));
            [...cards[1].querySelectorAll<HTMLLabelElement>('label.check-line')]
                .find(label => label.textContent?.includes('Первая буква'))!.querySelector('input')!.click();
            refresh();
            cloze = component().drafts().CLOZE;
            expect(cloze.blanks[1].size).toEqual({ mode: 'FIXED', length: 8 });
            expect(cloze.blanks[1].firstLetterHint).toBeTrue();
            expect(cloze.blanks[0].firstLetterHint).toBeFalse();

            component().setObjectiveTitle('map');
            component().save();
            const passage = (created().exercise.content as unknown as { passage: unknown[] }).passage;
            expect(passage).toEqual([
                { kind: 'BLANK', blankId: cloze.blanks[0].blankId, size: { mode: 'FIXED', length: 12 }, firstLetterHint: false },
                { kind: 'TEXT', text: ' and ' },
                { kind: 'BLANK', blankId: cloze.blanks[1].blankId, size: { mode: 'FIXED', length: 8 }, firstLetterHint: true },
                { kind: 'TEXT', text: '\n    .toList()' }
            ]);
            expect(parseExerciseSpec(created().exercise)).toBeDefined();

            buttonByText('Убрать пропуск и вернуть текст').click(); refresh();
            cloze = component().drafts().CLOZE;
            expect(cloze.blanks.length).toBe(1);
            expect(cloze.texts).toEqual(['map and ', '\n    .toList()']);
        });

        it('refuses to save without a blank or when answer lengths do not match the answer-length size', () => {
            configure();
            select('CLOZE');
            component().setCloze({ ...component().drafts().CLOZE, texts: ['only text'] });
            component().save();
            expect(component().fieldErrors()['passage']).toContain('пропусков');
            expect(api.create).not.toHaveBeenCalled();

            const blank = { blankId: id('90'), size: { mode: 'ANSWER_LENGTH' as const }, firstLetterHint: false,
                answer: { rows: [{ id: 'a', value: 'map' }, { id: 'b', value: 'filter' }], normalization: ['TRIM' as const],
                    matchingMode: 'STRICT' as const } };
            component().setCloze({ prompt: [], texts: ['x ', ''], blanks: [blank] });
            component().save();
            expect(component().fieldErrors()['blank:' + blank.blankId]).toContain('одной длины');
        });

        it('plays the hint of an author blank through the preview endpoint, never the Study one', () => {
            configure(detailOf(mechanics['createCloze'].exercise));
            const surface = preview()!;
            surface.querySelector<HTMLButtonElement>('.cloze-hint')!.click(); refresh();
            expect(previewApi.hint).toHaveBeenCalledTimes(1);
            expect(surface.querySelector('.cloze-letter')?.textContent).toContain('m');
            expect(studyApi.hint).not.toHaveBeenCalled();
        });
    });

    it('edits MATCH between 2 and 6 pairs and keeps every item id stable', () => {
        configure();
        select('MATCH');
        const root = page();
        expect(root.querySelectorAll('app-match-editor [data-pair]').length).toBe(2);
        expect(root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Удалить пару 1"]')?.disabled).toBeTrue();
        const before = component().drafts().MATCH.pairs.map(pair => pair.left.itemId + pair.right.itemId);
        for (let index = 0; index < 5; index++) root.querySelector<HTMLButtonElement>('[data-add-pair]')!.click();
        refresh();
        expect(component().drafts().MATCH.pairs.length).toBe(6);
        expect(root.querySelector<HTMLButtonElement>('[data-add-pair]')?.disabled).toBeTrue();
        expect(component().drafts().MATCH.pairs.slice(0, 2).map(pair => pair.left.itemId + pair.right.itemId)).toEqual(before);
        root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Опустить пару 1"]')!.click(); refresh();
        expect(component().drafts().MATCH.pairs[1].left.itemId + component().drafts().MATCH.pairs[1].right.itemId).toBe(before[0]);
        for (let index = 0; index < 4; index++) root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Удалить пару 1"]')!.click();
        refresh();
        expect(component().drafts().MATCH.pairs.length).toBe(2);
    });

    it('checks a pair of the author\'s match through the preview endpoint and shows the wrong-pair message', () => {
        configure(detailOf(mechanics['createMatchMixed'].exercise));
        previewApi.checkPair.and.returnValue(of(false));
        const surface = preview()!;
        surface.querySelector<HTMLButtonElement>('button[data-side="left"]')!.click(); refresh();
        surface.querySelector<HTMLButtonElement>('button[data-side="right"]')!.click(); refresh();
        expect(previewApi.checkPair).toHaveBeenCalledTimes(1);
        expect(surface.textContent).toContain('Эта пара не подходит');
        expect(studyApi.checkPair).not.toHaveBeenCalled();
    });

    it('adds a slot block of each kind within the profile and keeps COMPACT slots to one text and one media', () => {
        configure();
        select('CHOICE');
        const slot = page().querySelector('app-choice-editor [data-option] app-exercise-slot-editor')!;
        const add = (label: string) => buttonByText(label, slot);
        expect(add('+ Текст').disabled).toBeTrue();
        expect(add('+ Фрагмент материала').disabled).toBeTrue();
        expect(slot.textContent).not.toContain('+ YouTube');
        expect(slot.textContent).toContain('Не больше одного текста и одного изображения, аудио или видео');
        expect(slot.textContent).toContain('до 300 знаков');

        const prompt = page().querySelector('#step-prompt app-exercise-slot-editor')!;
        expect(prompt.textContent).toContain('до 4000 знаков');
        expect(prompt.textContent).toContain('+ YouTube');
        buttonByText('+ Фрагмент материала', prompt).click(); refresh();
        buttonByText('+ YouTube', prompt).click(); refresh();
        const blocks = component().drafts().CHOICE.prompt;
        expect(blocks.map(block => block.kind)).toEqual(['TEXT', 'MATERIAL', 'YOUTUBE']);
        expect(blocks[1]).toEqual({ kind: 'MATERIAL', memberKey: item.memberKey, itemRevisionId: item.itemRevisionId, nodeId: '' });
        const youtube = prompt.querySelector<HTMLInputElement>('input[id$="youtube-2"]')!;
        youtube.value = 'https://youtu.be/dQw4w9WgXcQ'; youtube.dispatchEvent(new Event('input')); refresh();
        expect(component().drafts().CHOICE.prompt[2]).toEqual({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: '' });
    });

    it('lets the author pick a fragment of the material only inside a slot, never rendering the whole material', () => {
        configure();
        select('SELF_CHECK');
        expect(page().textContent).not.toContain('Attention');
        buttonByText('+ Фрагмент материала', page().querySelector('#step-prompt')!).click(); refresh();
        component().setPrompt([text('Вопрос'), { ...component().drafts().SELF_CHECK.prompt[1], nodeId: '00000000-0000-4000-8000-000000000003' } as AuthoringBlock]);
        refresh();
        expect(page().querySelector('#step-prompt .preview-text')?.textContent).toBe('Ядро хранит ДНК');
        expect(page().textContent).not.toContain('Attention');
    });

    it('never truncates text: a block over its limit keeps all characters, shows the counter and blocks saving', () => {
        configure();
        select('SELF_CHECK');
        const long = 'я'.repeat(4001);
        component().setPrompt([text(long)]);
        component().setReference([text('ok')]);
        refresh();
        expect(page().querySelector('.counter.over')?.textContent).toContain('4001 / 4000');
        expect(component().drafts().SELF_CHECK.prompt[0]).toEqual(text(long));
        component().save(); refresh();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['prompt']).toContain('4000');
        expect(page().querySelector('#exercise-errors')?.textContent).toContain('4000');
    });

    it('shows the AI switch disabled with its badge and reason and never sends ai-semantic', () => {
        configure(null, { aiAssessment: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' }, speechToText: { available: false, reason: 'DISABLED' } });
        select('FREE_RESPONSE');
        const root = page();
        const toggle = root.querySelector<HTMLInputElement>('app-free-response-editor input[role="switch"]')!;
        expect(toggle.disabled).toBeTrue();
        expect(toggle.checked).toBeFalse();
        expect(root.querySelector('app-free-response-editor .badge')?.textContent?.trim()).toBe('ИИ');
        expect(root.textContent).toContain('Проверять смысл ответа с ИИ');
        expect(root.textContent).toContain('Проверка объяснений и формулировок по эталону. Пока недоступна.');
        expect(root.textContent).toContain('поставщик проверки не подключён');
        expect(root.textContent).toContain('Это не проверка смысла');
        toggle.click(); refresh();
        expect(toggle.checked).toBeFalse();

        component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Что такое инерция?')],
            answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'свойство тел' }], matchingMode: 'SOFT' } });
        component().save();
        const json = JSON.stringify(created().exercise);
        expect(json).not.toContain('ai-semantic');
        expect(json).not.toContain('rubric');
        expect(json).not.toContain('TEXT_OR_SPEECH');
        expect(created().exercise.evaluatorPolicy).toEqual({ id: 'deterministic-text', version: '1' });
        expect((created().exercise.answerKey as { matchingMode: string }).matchingMode).toBe('SOFT');
    });

    it('opens the optional reference of a free response only when it holds content', () => {
        configure();
        select('FREE_RESPONSE');
        expect(page().querySelector<HTMLDetailsElement>('details.optional')?.open).toBeFalse();
        component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, reference: [text('Подробный эталон')] }); refresh();
        expect(page().querySelector<HTMLDetailsElement>('details.optional')?.open).toBeTrue();
    });

    it('fails closed when capabilities cannot be read and for an exercise that needs an unavailable capability', () => {
        const ai = clone(mechanics['rejectedAiAssessment'].exercise) as ExerciseSpec;
        configure(detailOf(ai), 'error');
        expect(component().capabilities()).toEqual(CAPABILITIES_UNAVAILABLE);
        expect(page().querySelector<HTMLInputElement>('input[role="switch"]')?.disabled).toBeTrue();
        expect(page().querySelector<HTMLInputElement>('input[role="switch"]')?.checked).toBeTrue();
        component().save();
        expect(api.update).not.toHaveBeenCalled();
        expect(component().fieldErrors()['capability']).toContain('недоступна');
        // The preview of such an exercise is evaluated by the server, which reports it unavailable.
        expect(mode()).toBe('AUTHOR_READY');
    });

    it('keeps the draft when microphone permission is denied inside a slot and never inserts a block', async () => {
        configure();
        select('MATCH');
        const root = page();
        const typed = [text('der Hund')];
        const pair = component().drafts().MATCH.pairs[0];
        component().setMatch({ ...component().drafts().MATCH, pairs: [{ ...pair, left: { ...pair.left, blocks: typed } },
            ...component().drafts().MATCH.pairs.slice(1)] });
        refresh();
        expect(getUserMedia).not.toHaveBeenCalled();
        const slots = root.querySelectorAll('app-match-editor [data-pair] app-exercise-slot-editor');
        buttonByText('Добавить аудио', slots[1]).click(); refresh();
        expect(slots[1].querySelector('app-native-media-upload')).not.toBeNull();
        expect(slots[0].querySelector('app-native-media-upload')).toBeNull();
        const before = JSON.stringify(component().drafts());
        buttonByText('Записать аудио', slots[1]).click(); refresh();
        await fixture.whenStable(); refresh();
        expect(getUserMedia).toHaveBeenCalledTimes(1);
        expect(slots[1].textContent).toContain('Доступ к микрофону запрещён');
        expect(JSON.stringify(component().drafts())).toBe(before);
        expect(component().drafts().MATCH.pairs[0].left.blocks).toEqual(typed);
        expect(component().dirty()).toBeTrue();
    });

    it('inserts a chosen uploaded asset only into the slot whose picker produced it', () => {
        configure();
        select('MATCH');
        const slots = page().querySelectorAll('app-match-editor [data-pair] app-exercise-slot-editor');
        buttonByText('Добавить аудио', slots[2]).click(); refresh();
        buttonByText('Добавить аудио', slots[0]).click(); refresh();
        const upload = slots[2].querySelector('app-native-media-upload')!;
        const instance = fixture.debugElement.queryAll(el => el.nativeElement === upload)[0].componentInstance;
        const scrolled = scroll.calls.count();
        instance.chooseAsset.emit({ kind: 'audio', assetId: id('99') });
        refresh();
        const pairs = component().drafts().MATCH.pairs;
        expect(pairs[1].left.blocks.map(block => block.kind)).toEqual(['TEXT', 'AUDIO']);
        expect(pairs[1].left.blocks[1]).toEqual({ kind: 'AUDIO', assetId: id('99'), title: 'Аудио' });
        expect(pairs[0].left.blocks.map(block => block.kind)).toEqual(['TEXT']);
        expect(pairs[0].right.blocks.map(block => block.kind)).toEqual(['TEXT']);
        expect(pairs[1].right.blocks.map(block => block.kind)).toEqual(['TEXT']);
        // A background upload finishing is not an explicit step action: the page does not scroll.
        expect(scroll.calls.count()).toBe(scrolled);
        instance.chooseAsset.emit({ kind: 'image', assetId: id('98') });
        refresh();
        expect(component().drafts().MATCH.pairs[1].left.blocks.length).toBe(2);
        expect(slots[2].textContent).toContain('уже есть изображение, аудио или видео');
    });

    it('requires an image description and an author label for media blocks', () => {
        configure();
        select('SELF_CHECK');
        component().setPrompt([{ kind: 'IMAGE', assetId: id('99'), alt: '' }, text('?')]);
        component().setReference([{ kind: 'AUDIO', assetId: id('98'), title: ' ' }]);
        component().save(); refresh();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['prompt']).toContain('альтернативный текст обязателен');
        expect(component().fieldErrors()['reference']).toContain('Укажите название');
        expect(page().querySelector('[aria-invalid="true"]')).not.toBeNull();
    });

    describe('objective', () => {
        it('prefills the title from the question and lets the author override it', () => {
            configure();
            select('FREE_RESPONSE');
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Что  такое\nинерция?')],
                answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'x' }] } });
            refresh();
            expect(page().querySelector<HTMLInputElement>('#objective-title')?.value).toBe('Что такое инерция?');
            component().setObjectiveTitle('Инерция');
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('другое')] });
            expect(component().effectiveTitle()).toBe('Инерция');
            component().save();
            expect(created().objective).toEqual({ operation: 'create', title: 'Инерция' });
        });

        it('requires a name and keeps the suggestion within 160 characters without splitting a surrogate pair', () => {
            configure();
            select('FREE_RESPONSE');
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('😀'.repeat(200))],
                answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'x' }] } });
            expect(component().effectiveTitle().length).toBeLessThanOrEqual(160);
            expect(component().effectiveTitle()).not.toMatch(/[\uD800-\uDBFF]…$/u);
            component().setObjectiveTitle('   ');
            component().save();
            expect(component().fieldErrors()['objective-title']).toBeDefined();
            expect(api.create).not.toHaveBeenCalled();
        });

        it('revises an existing objective with its current revision', () => {
            configure(detailOf(mechanics['createSelfCheck'].exercise));
            component().setObjectiveMode('revise');
            component().setObjectiveTitle('Строение клетки');
            component().save();
            expect(api.update.calls.mostRecent().args[5] as unknown).toEqual({ operation: 'revise', objectiveId: objective.objectiveId,
                expectedObjectiveRevisionId: objective.objectiveRevisionId, title: 'Строение клетки' });
        });
    });

    describe('save failures', () => {
        function fill(): void {
            select('FREE_RESPONSE');
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Вопрос')],
                answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'ответ' }] } });
        }

        it('reports a version conflict, keeps the input, every step and the preview, and reloads on request', () => {
            configure();
            fill();
            api.create.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'VERSION_CONFLICT' } })));
            component().save(); refresh();
            expect(component().phase()).toBe('conflict');
            expect(page().textContent).toContain('изменились в другой вкладке');
            expect(component().drafts().FREE_RESPONSE.prompt).toEqual([text('Вопрос')]);
            expect(stepIds().length).toBe(3);
            expect(mode()).toBe('AUTHOR_READY');
            component().load();
            expect(api.list).toHaveBeenCalledTimes(2);
        });

        it('explains CAPABILITY_UNAVAILABLE separately from a conflict', () => {
            configure();
            fill();
            api.create.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'CAPABILITY_UNAVAILABLE' } })));
            component().save(); refresh();
            expect(component().phase()).toBe('rejected');
            expect(page().textContent).toContain('сейчас недоступна на сервере');
        });

        it('keeps the same command id when retrying after a network failure', () => {
            configure();
            fill();
            api.create.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            component().save();
            const commandId = api.create.calls.mostRecent().args[5];
            expect(component().phase()).toBe('error');
            expect(stepIds().length).toBe(3);
            api.create.and.returnValue(of({ acknowledgement, replayed: true }));
            component().retry();
            expect(api.create.calls.mostRecent().args[5]).toBe(commandId);
        });

        it('turns a contract rejection raised before the request into a visible message', () => {
            configure();
            fill();
            api.create.and.returnValue(throwError(() => new AuthoringProtocolError('Invalid block.')));
            component().save(); refresh();
            expect(component().phase()).toBe('rejected');
            expect(page().textContent).toContain('не прошло проверку формата');
        });

        it('does not create a second copy while a save is pending', () => {
            configure();
            fill();
            const pending = new Subject<never>();
            api.create.and.returnValue(pending);
            component().save(); component().save(); component().submit();
            expect(api.create).toHaveBeenCalledTimes(1);
        });

        it('does not let Enter in an earlier step save a half-built exercise', () => {
            configure();
            select('FREE_RESPONSE', false);
            page().querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
            expect(api.create).not.toHaveBeenCalled();
            expect(component().phase()).toBe('ready');
        });
    });

    it('warns when the exercise is pinned to an older material revision', () => {
        const spec = clone(mechanics['createSelfCheck'].exercise);
        spec.content.reference[0].itemRevisionId = 'ffffffff-ffff-4fff-8fff-ffffffffffff';
        configure(detailOf(spec));
        expect(page().textContent).toContain('закреплён за прежней версией');
        expect(page().querySelector('.notice.warning')).not.toBeNull();
    });

    it('guards unsaved changes, resets, deletes and respects the enable switch', () => {
        const detail = detailOf(mechanics['createSelfCheck'].exercise);
        configure(detail);
        const confirm = spyOn(window, 'confirm').and.returnValue(false);
        expect(component().canLeave()).toBeTrue();
        component().setEnabled(false);
        expect(component().canLeave()).toBeFalse();
        expect(confirm).toHaveBeenCalled();
        component().resetChanges();
        expect(component().enabled()).toBeTrue();
        expect(component().dirty()).toBeFalse();
        component().setEnabled(false);
        component().save();
        expect((api.update.calls.mostRecent().args[6] as ExerciseSpec).enabled).toBeFalse();
        component().deleteExercise();
        expect(api.delete).toHaveBeenCalledWith(deck.deckId, detail.exerciseId, deck.rowVersion);
    });

    it('typing is dirty, the trial of the preview is not', () => {
        configure();
        select('FREE_RESPONSE');
        preview()!.querySelector<HTMLButtonElement>('button[data-submit]')?.click();
        expect(component().dirty()).toBeFalse();
        type('#free-response-prompt-text-0', 'x');
        expect(component().dirty()).toBeTrue();
        const confirm = spyOn(window, 'confirm').and.returnValue(true);
        expect(component().canLeave()).toBeTrue();
        expect(confirm).toHaveBeenCalled();
    });

    describe('saved exercise and the list below', () => {
        it('shows the confirmation with an explicit «Создать ещё» after saving and lists the saved exercise', () => {
            const detail = detailOf(mechanics['createChoiceVideoMultiple'].exercise);
            savedQuery = '1';
            configure(detail);
            expect(component().phase()).toBe('saved');
            expect(page().querySelector('.notice')?.textContent).toContain('Упражнение сохранено.');
            const another = page().querySelector<HTMLAnchorElement>('[data-create-another]')!;
            expect(another.textContent?.trim()).toBe('Создать ещё');
            expect(page().querySelector('#existing-exercises li')?.textContent).toContain('Признаки реакции в опыте');
        });

        it('lists the exercises with catalog names, the open one marked, and loads more pages', () => {
            const detail = detailOf(mechanics['createChoiceVideoMultiple'].exercise);
            const other = detailOf(mechanics['createSelfCheck'].exercise, 1);
            configure(detail, CAPABILITIES_UNAVAILABLE, [{ ...other, exerciseId: id('60') }]);
            const rows = page().querySelectorAll('#existing-exercises li');
            expect(rows.length).toBe(2);
            expect(rows[0].textContent).toContain('Открыто сейчас');
            expect(rows[0].querySelector('a.button')).toBeNull();
            expect(rows[1].textContent).toContain('Вспомнить и сверить');
            component().page.set({ ...component().page()!, nextCursor: 'next' }); refresh();
            api.list.and.returnValue(of({ ...emptyPage, total: 3, exercises: [summaryOf(other, id('61'))], nextCursor: null }));
            buttonByText('Показать ещё').click(); refresh();
            expect(page().querySelectorAll('#existing-exercises li').length).toBe(3);
        });

        it('toggles another exercise through a normal revision and refreshes the deck pins without touching the open draft', () => {
            const other = { ...detailOf(mechanics['createSelfCheck'].exercise, 1), exerciseId: id('60'), exerciseRevisionId: id('62') };
            configure(null, CAPABILITIES_UNAVAILABLE, [other]);
            select('FREE_RESPONSE', false);
            type('#free-response-prompt-text-0', 'Мой вопрос');
            const checkbox = page().querySelector<HTMLInputElement>('#existing-exercises input[type="checkbox"]')!;
            checkbox.click(); refresh();
            const args = api.update.calls.mostRecent().args;
            expect(args[1]).toBe(id('60'));
            expect(args[4]).toBe(id('62'));
            expect(args[5] as unknown).toEqual({ operation: 'reuse', objectiveId: objective.objectiveId, objectiveRevisionId: objective.objectiveRevisionId });
            expect((args[6] as ExerciseSpec).enabled).toBeFalse();
            expect(Object.keys(args[6] as object).sort()).toEqual(['answerKey', 'content', 'enabled', 'evaluatorPolicy', 'schemaVersion', 'subject', 'type']);
            expect(decksApi.detail).toHaveBeenCalledTimes(2);
            expect(page().querySelector('#existing-exercises .notice')?.textContent).toContain('обновлена');
            expect(component().drafts().FREE_RESPONSE.prompt).toEqual([text('Мой вопрос')]);
        });

        it('deletes another exercise and reports failures without losing the draft', () => {
            const other = { ...detailOf(mechanics['createSelfCheck'].exercise, 1), exerciseId: id('60') };
            configure(null, CAPABILITIES_UNAVAILABLE, [other]);
            select('FREE_RESPONSE', false);
            type('#free-response-prompt-text-0', 'Мой вопрос');
            api.delete.and.returnValue(throwError(() => new HttpErrorResponse({ status: 412 })));
            component().deleteListed(component().page()!.exercises[0]); refresh();
            expect(page().querySelector('#existing-exercises .notice')?.textContent).toContain('Колода изменилась');
            expect(component().listBusy()).toBeNull();
            api.delete.and.returnValue(of(undefined));
            component().deleteListed(component().page()!.exercises[0]); refresh();
            expect(api.delete).toHaveBeenCalledWith(deck.deckId, id('60'), deck.rowVersion);
            expect(page().querySelector('#existing-exercises .notice')?.textContent).toContain('удалено');
            expect(component().drafts().FREE_RESPONSE.prompt).toEqual([text('Мой вопрос')]);
        });
    });

    it('has no horizontal overflow at 320, 390 and 1440 with long strings in every mechanic, preview and list included', () => {
        const other = { ...detailOf(mechanics['createSelfCheck'].exercise, 1), exerciseId: id('60') };
        configure(null, CAPABILITIES_UNAVAILABLE, [other]);
        const long = 'я'.repeat(300);
        const root = page(); root.style.display = 'block';
        for (const kind of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH'] as const) {
            select(kind);
            component().setPrompt([text(long)]);
            if (kind === 'SELF_CHECK') component().setReference([text(long)]);
            refresh();
            for (const width of [320, 390, 1440]) {
                root.style.width = `${width}px`; refresh();
                expect(root.scrollWidth).withContext(`${kind} at ${width}`).toBeLessThanOrEqual(width + 1);
            }
        }
    });
});

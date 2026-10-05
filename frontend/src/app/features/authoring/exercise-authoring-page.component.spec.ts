import type { Mock } from "vitest";
import { HttpErrorResponse } from '@angular/common/http';
import { ApplicationRef } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';

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
import { spyObj, type SpyObj, lastCall } from '../../../testing/mocks';
import { ToastService } from '../../core/notifications/toast.service';
import { GenerationApiService } from '../generation/generation-api.service';
import { ArtifactDetail, RequestValidationError, parseApprovalAck, parseArtifactDetail } from '../generation/generation.models';
import { ack, createCommand, exerciseDetail } from '../generation/exercise-test-data';
import { ids as generationIds, problemResponse } from '../generation/generation-test-data';

describe('ExerciseAuthoringPageComponent', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const subject = mechanics['createSelfCheck'].exercise.subject as {
        memberKey: string;
        itemRevisionId: string;
    };
    const deck: OwnDeck = {
        deckId: '11111111-1111-4111-8111-111111111111', revisionId: '22222222-2222-4222-8222-222222222222', rowVersion: '3',
        sequence: '3', metadata: { title: 'Память', description: '' }, visibility: 'private',
        createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', memberCount: 1, exerciseCount: 0
    };
    const paragraph = (nodeId: string, text: string) => ({ id: nodeId, type: 'paragraph', version: 1, attrs: {}, content: [
            { id: id(`7${nodeId.slice(-2)}`), type: 'text', version: 1, attrs: { text }, content: [] }
        ] });
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

    let api: SpyObj<ExerciseApiService>;
    let previewApi: SpyObj<ExercisePreviewApiService>;
    let studyApi: SpyObj<StudyApiService>;
    let decksApi: SpyObj<OwnDecksApiService>;
    let capabilityApi: SpyObj<CapabilitiesApiService>;
    let router: SpyObj<Router>;
    let fixture: ComponentFixture<ExerciseAuthoringPageComponent>;
    let getUserMedia: Mock;
    let scroll: Mock;
    let savedQuery = '';
    let extraQuery: Record<string, string> = {};
    let proposalAnswer: Observable<ArtifactDetail> | null = null;
    let newMarks = new Set<string>();
    let generationApi: SpyObj<GenerationApiService>;
    let toast: { echo: ReturnType<typeof vi.fn> };

    function detailOf(spec: ExerciseSpec, ordinal = 0): ExerciseDetail {
        return { exerciseId: id('50'), exerciseRevisionId: id('51'), exerciseVersion: '1', ordinal,
            createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', objective,
            deckId: deck.deckId, deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion, ...spec };
    }

    function summaryOf(detail: ExerciseDetail, exerciseId = detail.exerciseId) {
        return { exerciseId, exerciseRevisionId: detail.exerciseRevisionId, exerciseVersion: '1', ordinal: detail.ordinal,
            type: detail.type, enabled: detail.enabled, schemaVersion: 2 as const, createdAt: detail.createdAt,
            updatedAt: detail.updatedAt, objective: detail.objective, isNew: newMarks.has(exerciseId) };
    }

    /** `detail` opens the edit route; `others` are further exercises of the material already listed. */
    function configure(detail: ExerciseDetail | null = null, capabilities: LearningCapabilities | 'error' = CAPABILITIES_UNAVAILABLE, others: readonly ExerciseDetail[] = []): void {
        decksApi = spyObj<OwnDecksApiService>({
            detail: vi.fn().mockName("OwnDecksApiService.detail")
        });
        generationApi = spyObj<GenerationApiService>({ getArtifact: vi.fn(), approveArtifact: vi.fn() });
        generationApi.getArtifact.mockImplementation(() => proposalAnswer ?? throwError(() => new HttpErrorResponse({ status: 500 })));
        toast = { echo: vi.fn() };
        const items = {
            read: vi.fn().mockName("ItemApiService.read")
        };
        api = spyObj<ExerciseApiService>({
            list: vi.fn().mockName("ExerciseApiService.list"),
            read: vi.fn().mockName("ExerciseApiService.read"),
            create: vi.fn().mockName("ExerciseApiService.create"),
            update: vi.fn().mockName("ExerciseApiService.update"),
            delete: vi.fn().mockName("ExerciseApiService.delete"),
            clearNewMark: vi.fn().mockName("ExerciseApiService.clearNewMark")
        });
        capabilityApi = spyObj<CapabilitiesApiService>({
            read: vi.fn().mockName("CapabilitiesApiService.read")
        });
        previewApi = spyObj<ExercisePreviewApiService>({
            submit: vi.fn().mockName("ExercisePreviewApiService.submit"),
            checkPair: vi.fn().mockName("ExercisePreviewApiService.checkPair"),
            hint: vi.fn().mockName("ExercisePreviewApiService.hint")
        });
        studyApi = spyObj<StudyApiService>({
            start: vi.fn().mockName("StudyApiService.start"),
            read: vi.fn().mockName("StudyApiService.read"),
            refill: vi.fn().mockName("StudyApiService.refill"),
            submit: vi.fn().mockName("StudyApiService.submit"),
            checkPair: vi.fn().mockName("StudyApiService.checkPair"),
            hint: vi.fn().mockName("StudyApiService.hint"),
            revealTranscript: vi.fn().mockName("StudyApiService.revealTranscript")
        });
        router = spyObj<Router>({
            navigate: vi.fn().mockName("Router.navigate")
        });
        decksApi.detail.mockReturnValue(of(deck));
        items.read.mockReturnValue(of(item));
        const listed = [...(detail === null ? [] : [summaryOf(detail)]), ...others.map(other => summaryOf(other))];
        api.list.mockReturnValue(of({ ...emptyPage, total: listed.length, exercises: listed }));
        api.read.mockImplementation((_deckId, exerciseId) => of(exerciseId === id('50') && detail !== null ? detail
            : others.find(other => other.exerciseId === exerciseId) ?? detailOf(mechanics['createSelfCheck'].exercise)));
        api.create.mockReturnValue(of({ acknowledgement, replayed: false }));
        api.update.mockReturnValue(of({ acknowledgement, replayed: false }));
        api.delete.mockReturnValue(of(undefined));
        api.clearNewMark.mockReturnValue(of(undefined));
        capabilityApi.read.mockReturnValue(capabilities === 'error' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(capabilities));
        router.navigate.mockResolvedValue(true);
        previewApi.submit.mockReturnValue(of({ result: 'CORRECT', appliedRules: [] } as AttemptFeedback));
        previewApi.checkPair.mockReturnValue(of(true));
        previewApi.hint.mockReturnValue(of('m'));
        const playback = {
            resolve: vi.fn().mockName("MediaPlaybackResolver.resolve")
        };
        playback.resolve.mockImplementation(fakePlayback);
        const upload = {
            policy: vi.fn().mockName("NativeMediaUploadApi.policy"),
            intent: vi.fn().mockName("NativeMediaUploadApi.intent"),
            status: vi.fn().mockName("NativeMediaUploadApi.status"),
            partUrls: vi.fn().mockName("NativeMediaUploadApi.partUrls"),
            completedParts: vi.fn().mockName("NativeMediaUploadApi.completedParts"),
            finalize: vi.fn().mockName("NativeMediaUploadApi.finalize"),
            retry: vi.fn().mockName("NativeMediaUploadApi.retry"),
            cancel: vi.fn().mockName("NativeMediaUploadApi.cancel"),
            renewSingle: vi.fn().mockName("NativeMediaUploadApi.renewSingle"),
            put: vi.fn().mockName("NativeMediaUploadApi.put")
        };
        getUserMedia = vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockImplementation(() => Promise.reject(new DOMException('denied', 'NotAllowedError')));
        scroll = vi.spyOn(HTMLElement.prototype, 'scrollIntoView').mockReturnValue(undefined);
        TestBed.configureTestingModule({ providers: [
                { provide: ActivatedRoute, useValue: { snapshot: {
                            paramMap: convertToParamMap({ deckId: deck.deckId, ...(detail ? { exerciseId: detail.exerciseId } : { memberKey: item.memberKey }) }),
                            queryParamMap: convertToParamMap({ ...(savedQuery === '' ? {} : { saved: savedQuery }), ...extraQuery })
                        } } },
                { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decksApi },
                { provide: ItemApiService, useValue: items }, { provide: ExerciseApiService, useValue: api },
                { provide: ExercisePreviewApiService, useValue: previewApi }, { provide: StudyApiService, useValue: studyApi },
                { provide: CapabilitiesApiService, useValue: capabilityApi }, { provide: MEDIA_PLAYBACK_RESOLVER, useValue: playback },
                { provide: NativeMediaUploadApi, useValue: upload },
                { provide: GenerationApiService, useValue: generationApi }, { provide: ToastService, useValue: toast }
            ] });
        fixture = TestBed.createComponent(ExerciseAuthoringPageComponent);
        fixture.detectChanges();
    }

    beforeEach(() => { savedQuery = ''; extraQuery = {}; proposalAnswer = null; newMarks = new Set<string>(); });

    const page = () => fixture.nativeElement as HTMLElement;
    const component = () => fixture.componentInstance;
    const text = (value: string): AuthoringBlock => ({ kind: 'TEXT', text: value });
    const refresh = () => { fixture.detectChanges(); TestBed.inject(ApplicationRef).tick(); fixture.detectChanges(); };

    /** Picks a tile like an author would, then (optionally) opens every step as a completed flow would. */
    function select(type: Mechanic, openAll = true): void {
        component().choose({ mechanic: type, deliberate: false });
        refresh();
        if (component().pendingSwitch() !== null) {
            component().confirmSwitch();
            refresh();
        }
        if (openAll) {
            component().opened.update(current => ({ ...current, [type]: catalogEntry(type).steps.length }));
            refresh();
        }
    }

    function buttonByText(label: string, root: ParentNode = page()): HTMLButtonElement {
        const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === label);
        if (button === undefined)
            throw new Error(`Missing button ${label}`);
        return button;
    }

    function created(): {
        objective: Record<string, unknown>;
        exercise: ExerciseSpec;
    } {
        const args = lastCall(api.create);
        return { objective: args[3] as unknown as Record<string, unknown>, exercise: args[4] };
    }

    const radio = (type: Mechanic) => page().querySelector<HTMLInputElement>(`input[name="mechanic"][value="${type}"]`)!;
    const preview = () => page().querySelector<HTMLElement>('app-exercise-preview-host');
    const mode = () => preview()?.querySelector('.preview')?.getAttribute('data-mode') ?? null;
    const stepIds = () => [...page().querySelectorAll('section.step')].map(step => step.id);

    function moveOn(): void { buttonByText('Продолжить').click(); refresh(); }

    function type(selector: string, value: string): void {
        const field = page().querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
        field.value = value;
        field.dispatchEvent(new Event('input'));
        refresh();
    }

    describe('initial states', () => {
        it('a new exercise shows the type choice alone: no preview, no step, no list, no side column, no material block', () => {
            configure();
            expect(getUserMedia).not.toHaveBeenCalled();
            expect(page().querySelector('app-mechanic-picker legend')?.textContent).toBe('1. Тип упражнения');
            const names = [...page().querySelectorAll('.tile-title')].map(node => node.textContent);
            expect(names).toEqual(['Вспомнить и сверить', 'Ввести ответ', 'Заполнить пропуски', 'Выбрать ответ', 'Сопоставить элементы',
                'Восстановить порядок', 'Распределить по группам']);
            expect([...page().querySelectorAll('input[name="mechanic"]')].some(input => (input as HTMLInputElement).checked)).toBe(false);
            expect(component().mechanic()).toBeNull();
            expect(preview()).toBeNull();
            expect(stepIds()).toEqual([]);
            expect(page().querySelector('.existing, #existing-exercises, .jump-link, aside, .preview-column, .material-card')).toBeNull();
            expect(page().textContent).not.toContain('Актуальный материал');
            expect(page().querySelector('app-native-media-surface')).toBeNull();
            for (const legacy of removedNames)
                expect(page().innerHTML).not.toContain(legacy);
            expect(page().querySelector('[data-continue]')).toBeNull();
            expect(page().querySelector('button[type="submit"]')).toBeNull();
        });

        it('uses the catalog texts for the tiles and makes the whole tile a native radio inside a label', () => {
            configure();
            const tiles = [...page().querySelectorAll('label.tile')];
            expect(tiles.length).toBe(7);
            expect(tiles[1].textContent).toContain('Ученик напишет ответ на ваш вопрос.');
            for (const tile of tiles)
                expect(tile.querySelector('input[type="radio"][name="mechanic"]')).not.toBeNull();
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
            expect(page().querySelectorAll('label.tile').length).toBe(7);
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
            expect(event.defaultPrevented).toBe(true);
            expect(scroll).toHaveBeenCalledTimes(1);
            expect(router.navigate).not.toHaveBeenCalled();
        });
    });

    describe('tile activation, preview and scrolling', () => {
        it('a click on a tile reveals the preview and the first step and scrolls to the preview exactly once', () => {
            configure();
            const label = radio('CHOICE').closest('label')!;
            label.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            radio('CHOICE').click();
            refresh();
            expect(component().mechanic()).toBe('CHOICE');
            expect(preview()).not.toBeNull();
            expect(stepIds()).toEqual(['step-prompt']);
            expect(page().querySelector('#step-prompt h2')?.textContent?.trim()).toBe('2. Вопрос');
            expect(scroll).toHaveBeenCalledTimes(1);
            expect((scroll.mock.contexts.at(-1) as HTMLElement).id).toBe('exercise-preview-anchor');
            expect(lastCall(scroll)[0]).toEqual({ block: 'start', behavior: 'smooth' });
            // Switching to the next tile on an empty form changes the example and scrolls again, once.
            radio('MATCH').closest('label')!.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            radio('MATCH').click();
            refresh();
            expect(scroll).toHaveBeenCalledTimes(2);
            expect(component().pendingSwitch()).toBeNull();
        });

        it('arrow keys in the group select a tile without scrolling or moving focus', () => {
            configure();
            const first = radio('SELF_CHECK');
            first.focus();
            first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
            first.click();
            refresh();
            expect(component().mechanic()).toBe('SELF_CHECK');
            expect(preview()).not.toBeNull();
            expect(scroll).not.toHaveBeenCalled();
            const next = radio('FREE_RESPONSE');
            next.focus();
            next.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
            next.click();
            refresh();
            expect(component().mechanic()).toBe('FREE_RESPONSE');
            expect(scroll).not.toHaveBeenCalled();
            expect(document.activeElement).toBe(next);
        });

        it('activating the tile that is already chosen takes the author back to the example', () => {
            configure();
            select('SELF_CHECK', false);
            scroll.mockClear();
            radio('SELF_CHECK').click();
            expect(scroll).toHaveBeenCalledTimes(1);
            expect(component().mechanic()).toBe('SELF_CHECK');
        });

        it('respects reduced motion: the jump is instant', () => {
            configure();
            const original = window.matchMedia.bind(window);
            vi.spyOn(window, 'matchMedia').mockImplementation((value: string) => value.includes('prefers-reduced-motion')
                ? { matches: true, media: value } as MediaQueryList : original(value));
            component().choose({ mechanic: 'CLOZE', deliberate: true });
            refresh();
            expect(lastCall(scroll)[0]).toEqual({ block: 'start', behavior: 'auto' });
        });

        it('never scrolls or moves focus while the author types, composes or uploads', () => {
            configure();
            select('FREE_RESPONSE', false);
            scroll.mockClear();
            const area = page().querySelector<HTMLTextAreaElement>('#free-response-prompt-text-0')!;
            area.focus();
            area.dispatchEvent(new CompositionEvent('compositionstart'));
            for (const value of ['К', 'Ка', 'Как'])
                type('#free-response-prompt-text-0', value);
            area.dispatchEvent(new CompositionEvent('compositionend'));
            area.dispatchEvent(new Event('blur'));
            expect(scroll).not.toHaveBeenCalled();
            expect(document.activeElement).toBe(area);
            expect(stepIds()).toEqual(['step-prompt']);
        });

        it('opens the next step only on an explicit «Продолжить», brings it into view and keeps the focus on its heading', () => {
            configure();
            select('FREE_RESPONSE', false);
            scroll.mockClear();
            buttonByText('Продолжить').click();
            refresh();
            expect(stepIds()).toEqual(['step-prompt']);
            expect(page().querySelector('#step-prompt .step-problem')?.textContent).toContain('Чтобы продолжить, исправьте');
            expect(scroll).not.toHaveBeenCalled();

            type('#free-response-prompt-text-0', 'Столица Франции?');
            expect(page().querySelector('#step-prompt .step-problem')).toBeNull();
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-answers']);
            expect(scroll).toHaveBeenCalledTimes(1);
            expect((scroll.mock.contexts.at(-1) as HTMLElement).id).toBe('step-answers');
            expect(document.activeElement?.id).toBe('step-answers-title');
            expect(page().querySelector('#step-answers h2')?.textContent?.trim()).toBe('3. Допустимые ответы');
            expect(page().querySelectorAll('[data-continue]').length).toBe(1);

            // Settings of an open step update the preview but never jump to it.
            scroll.mockClear();
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
                MATCH: ['2. Общая инструкция', '3. Пары', '4. Название и сохранение'],
                ORDER: ['2. Инструкция', '3. Элементы в правильном порядке', '4. Название и сохранение'],
                CATEGORIZE: ['2. Инструкция', '3. Названия групп', '4. Элементы и их группы', '5. Название и сохранение']
            };
            configure();
            for (const mechanic of Object.keys(expected) as Mechanic[]) {
                select(mechanic);
                expect([...page().querySelectorAll('.step-title')].map(node => node.textContent?.trim()), mechanic).toEqual(expected[mechanic]);
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
                expect(mode(), mechanic).toBe('DEMO');
                expect(preview()!.querySelector('.stamp')?.textContent?.trim()).toBe('Пример');
                expect(preview()!.querySelector('[data-preview-caption]')?.textContent)
                    .toContain('Это пример упражнения. Заполните шаги ниже — здесь появится ваше задание');
                expect(preview()!.querySelector('app-learner-exercise')).not.toBeNull();
            }
            // Selecting tiles only explores: nothing was authored, so leaving the page is free.
            expect(component().dirty()).toBe(false);
            expect(component().canLeave()).toBe(true);
            expect(studyApi.start).not.toHaveBeenCalled();
        });

        it('lets the author play the demo through the preview endpoint only and restart it without touching the form', () => {
            configure();
            select('CHOICE', false);
            preview()!.querySelector<HTMLInputElement>('input[type="radio"]')!.click();
            refresh();
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click();
            refresh();
            expect(previewApi.submit).toHaveBeenCalledTimes(1);
            const [exercise, submission] = lastCall(previewApi.submit);
            expect(exercise.type).toBe('CHOICE');
            expect(submission.response.kind).toBe('CHOICE');
            expect(preview()!.querySelector('#preview-result-title')?.textContent).toBe('Верно');
            expect(preview()!.textContent).toContain('Пример завершён');
            const before = JSON.stringify(component().drafts());
            buttonByText('Начать заново', preview()!).click();
            refresh();
            expect(preview()!.querySelector('app-learner-exercise')).not.toBeNull();
            expect(preview()!.querySelector('#preview-result-title')).toBeNull();
            expect(JSON.stringify(component().drafts())).toBe(before);
            expect(component().dirty()).toBe(false);
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
            for (const kind of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH', 'ORDER', 'CATEGORIZE'] as const) {
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
            expect(preview()!.querySelector('.stamp')?.textContent?.trim()).toBe('Ваше задание');
            expect(preview()!.querySelector('app-learner-media')).toBeNull();
            const submit = preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!;
            expect(submit.disabled).toBe(true);
            expect(preview()!.querySelector('.blocked')?.textContent).toContain('Проверить ответ пока нельзя');
            expect(previewApi.submit).not.toHaveBeenCalled();
        });

        it('shows the neutral placeholders for every unfinished part', () => {
            configure();
            select('SELF_CHECK', false);
            type('#self-check-prompt-text-0', 'Что такое ДНК?');
            expect(preview()!.textContent).toContain('Что такое ДНК?');
            preview()!.querySelector<HTMLButtonElement>('[data-answer-control]')!.click();
            refresh();
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
            answer.value = 'париж';
            answer.dispatchEvent(new Event('input'));
            refresh();
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click();
            refresh();
            const [exercise, submission] = lastCall(previewApi.submit);
            expect(JSON.stringify(exercise)).toContain('Париж');
            expect(JSON.stringify(exercise)).not.toContain('de000000');
            expect(exercise).not.toEqual(expect.objectContaining({ subject: expect.anything() }));
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
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click();
            refresh();
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
            previewApi.submit.mockReturnValue(late);
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click();
            refresh();
            type('#free-response-prompt-text-0', 'Вопрос изменён');
            late.next({ result: 'CORRECT', appliedRules: [] });
            refresh();
            expect(preview()!.querySelector('#preview-result-title')).toBeNull();
            expect(preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.disabled).toBe(false);
        });

        it('opening an existing exercise shows its own content in the preview, never the demo', () => {
            const detail = detailOf(mechanics['createSelfCheck'].exercise);
            configure(detail);
            expect(mode()).toBe('AUTHOR_READY');
            expect(preview()!.querySelector('.stamp')?.textContent?.trim()).toBe('Ваше задание');
            expect(preview()!.textContent).not.toContain('Канберра');
            expect(preview()!.textContent).toContain('Назовите органеллы');
            // Even after clearing everything an existing exercise falls back to a draft, not to the example.
            component().setPrompt([text('')]);
            component().setReference([text('')]);
            refresh();
            expect(mode()).toBe('AUTHOR_DRAFT');
        });

        it('shows the voice input and the semantic AI check as unavailable with a reason and never inside the demo', () => {
            configure(null, { ...CAPABILITIES_UNAVAILABLE });
            select('FREE_RESPONSE');
            const voice = preview()!.querySelector<HTMLButtonElement>('.voice button')!;
            expect(voice.disabled).toBe(true);
            expect(preview()!.querySelector('.voice .hint')?.textContent).toContain('Голосовой ответ пока недоступен');
            const toggle = page().querySelector<HTMLInputElement>('input[role="switch"]')!;
            expect(toggle.disabled).toBe(true);
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
                correctIds: spec.answerKey.correctOptionIds });
            refresh();
            const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
            scroll.mockClear();
            radio('MATCH').click();
            refresh();
            expect(confirm).not.toHaveBeenCalled();
            const dialog = page().querySelector('.switch-confirm')!;
            expect(dialog.getAttribute('role')).toBe('alertdialog');
            expect(dialog.textContent).toContain('Переключиться на «Сопоставить элементы»?');
            expect(dialog.textContent).toContain('варианты ответа и отметки правильных');
            expect(component().mechanic()).toBe('CHOICE');
            expect(radio('MATCH').checked).toBe(true);
            expect(scroll).not.toHaveBeenCalled();

            buttonByText('Отмена', dialog).click();
            refresh();
            expect(page().querySelector('.switch-confirm')).toBeNull();
            expect(component().mechanic()).toBe('CHOICE');
            expect(radio('CHOICE').checked).toBe(true);
            expect(radio('MATCH').checked).toBe(false);
            expect(component().drafts().CHOICE.correctIds).toEqual(spec.answerKey.correctOptionIds);
            expect(component().drafts().CHOICE.selectionMode).toBe('MULTIPLE');
            expect(document.activeElement).toBe(radio('CHOICE'));
        });

        it('keeps the old draft in memory when the author confirms, and asks again on the way back', () => {
            configure();
            select('CHOICE');
            const spec = mechanics['createChoiceVideoMultiple'].exercise;
            component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
                correctIds: spec.answerKey.correctOptionIds });
            refresh();
            scroll.mockClear();
            radio('MATCH').click();
            refresh();
            buttonByText('Переключиться').click();
            refresh();
            expect(component().mechanic()).toBe('MATCH');
            expect(component().pendingSwitch()).toBeNull();
            expect(scroll).toHaveBeenCalledTimes(1);
            expect(component().drafts().CHOICE.options).toEqual(spec.content.options);
            // The hidden data is not saved with the new type: only the carried question and an empty pair list exist.
            component().opened.update(current => ({ ...current, MATCH: 3 }));
            refresh();
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
            expect(component().dirty()).toBe(true);
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
            expect(component().dirty()).toBe(false);
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
            expect(lastCall(api.update)[5] as unknown).toEqual({ operation: 'reuse',
                objectiveId: objective.objectiveId, objectiveRevisionId: objective.objectiveRevisionId });
            expect(lastCall(api.update)[6]).toEqual(mechanics['createCloze'].exercise);
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
            component().setMatch({ prompt: spec.content.prompt, pairs: spec.content.left.map((left: {
                    itemId: string;
                    blocks: AuthoringBlock[];
                }, index: number) => ({
                    pairId: `pair-${index}`, left, right: spec.content.right[index]
                })) });
            component().save();
            expect(created().exercise).toEqual(spec);
            expect(parseExerciseSpec(created().exercise)).toEqual(spec);
        });

        it('never saves the demo: a pristine form is refused and no demo id reaches a saved payload', () => {
            configure();
            select('CHOICE');
            expect(mode()).toBe('DEMO');
            component().save();
            refresh();
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
        const cases: Array<[
            Mechanic,
            string
        ]> = [['SELF_CHECK', 'createSelfCheck'], ['FREE_RESPONSE', 'createFreeResponseAudio'],
            ['CLOZE', 'createCloze'], ['CHOICE', 'createChoiceVideoMultiple'], ['MATCH', 'createMatchMixed'], ['ORDER', 'createOrder'],
            ['CATEGORIZE', 'createCategorize']];
        for (const [kind, name] of cases) {
            it(`${kind}`, () => {
                const spec: ExerciseSpec = mechanics[name].exercise;
                configure(detailOf(spec));
                expect(component().mechanic()).toBe(kind);
                expect(component().objectiveMode()).toBe('reuse');
                // Every relevant step is already open and filled in; nothing asks to be filled again.
                expect(stepIds().length).toBe(catalogEntry(kind).steps.length);
                expect(page().querySelector('[data-continue]')).toBeNull();
                expect(radio(kind).checked).toBe(true);
                expect(mode()).toBe('AUTHOR_READY');
                component().save();
                expect(lastCall(api.update)[6]).toEqual(spec);
                expect(lastCall(api.update)[4]).toBe(id('51'));
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

        component().save();
        refresh();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['selection']).toContain('Снимите лишние отметки');
        expect(component().phase()).toBe('rejected');

        // Unchecking one mark fixes it without losing the other one.
        page().querySelectorAll<HTMLInputElement>('app-choice-editor .card > .check-line input[type="checkbox"]')[0].click();
        refresh();
        expect(component().drafts().CHOICE.correctIds).toEqual([spec.answerKey.correctOptionIds[1]]);
        expect(page().querySelector('[id$="selection-error"]')).toBeNull();
    });

    it('marks, adds, reorders and removes choice options within 2 to 12', () => {
        configure();
        select('CHOICE');
        const root = page();
        expect(root.querySelectorAll('app-choice-editor [data-option]').length).toBe(2);
        expect(root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Удалить вариант 1"]')?.disabled).toBe(true);
        for (let index = 0; index < 10; index++)
            root.querySelector<HTMLButtonElement>('[data-add-option]')!.click();
        refresh();
        expect(root.querySelectorAll('app-choice-editor [data-option]').length).toBe(12);
        expect(root.querySelector<HTMLButtonElement>('[data-add-option]')?.disabled).toBe(true);
        const first = component().drafts().CHOICE.options[0].optionId;
        root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Опустить вариант 1"]')!.click();
        refresh();
        expect(component().drafts().CHOICE.options[1].optionId).toBe(first);
        root.querySelector<HTMLInputElement>('app-choice-editor .card > .check-line input[type="checkbox"]')!.click();
        refresh();
        expect(component().drafts().CHOICE.correctIds.length).toBe(1);
        root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Удалить вариант 1"]')!.click();
        refresh();
        expect(component().drafts().CHOICE.options.length).toBe(11);
        expect(component().drafts().CHOICE.correctIds.length).toBe(0);
    });

    describe('cloze passage editing', () => {
        function selectIn(index: number, start: number, end: number): void {
            const area = page().querySelector<HTMLTextAreaElement>(`#cloze-text-${index}`)!;
            area.focus();
            area.setSelectionRange(start, end);
            area.dispatchEvent(new Event('select'));
            refresh();
        }
        function typeIn(index: number, value: string): void { type(`#cloze-text-${index}`, value); }

        it('adds, edits and deletes blanks; repeated words get their own blank ids and nothing is lost', () => {
            configure();
            select('CLOZE');
            typeIn(0, 'map and map\n    .toList()');
            selectIn(0, 8, 11);
            buttonByText('Сделать пропуском').click();
            refresh();
            let cloze = component().drafts().CLOZE;
            expect(cloze.texts).toEqual(['map and ', '\n    .toList()']);
            expect(cloze.blanks.length).toBe(1);
            expect(cloze.blanks[0].answer.rows[0].value).toBe('map');
            // The preview shows the passage as soon as it has a blank.
            expect(preview()!.querySelectorAll('app-cloze-passage input').length).toBe(1);

            selectIn(0, 0, 3);
            buttonByText('Сделать пропуском').click();
            refresh();
            cloze = component().drafts().CLOZE;
            expect(cloze.texts).toEqual(['', ' and ', '\n    .toList()']);
            expect(cloze.blanks.map(blank => blank.answer.rows[0].value)).toEqual(['map', 'map']);
            expect(new Set(cloze.blanks.map(blank => blank.blankId)).size).toBe(2);

            const cards = page().querySelectorAll('[data-blank]');
            expect(cards.length).toBe(2);
            const length = cards[1].querySelector<HTMLInputElement>('input[type="number"]')!;
            length.value = '8';
            length.dispatchEvent(new Event('input'));
            [...cards[1].querySelectorAll<HTMLLabelElement>('label.check-line')]
                .find(label => label.textContent?.includes('Первая буква'))!.querySelector('input')!.click();
            refresh();
            cloze = component().drafts().CLOZE;
            expect(cloze.blanks[1].size).toEqual({ mode: 'FIXED', length: 8 });
            expect(cloze.blanks[1].firstLetterHint).toBe(true);
            expect(cloze.blanks[0].firstLetterHint).toBe(false);

            component().setObjectiveTitle('map');
            component().save();
            const passage = (created().exercise.content as unknown as {
                passage: unknown[];
            }).passage;
            expect(passage).toEqual([
                { kind: 'BLANK', blankId: cloze.blanks[0].blankId, size: { mode: 'FIXED', length: 12 }, firstLetterHint: false },
                { kind: 'TEXT', text: ' and ' },
                { kind: 'BLANK', blankId: cloze.blanks[1].blankId, size: { mode: 'FIXED', length: 8 }, firstLetterHint: true },
                { kind: 'TEXT', text: '\n    .toList()' }
            ]);
            expect(parseExerciseSpec(created().exercise)).toBeDefined();

            buttonByText('Убрать пропуск и вернуть текст').click();
            refresh();
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
            surface.querySelector<HTMLButtonElement>('.cloze-hint')!.click();
            refresh();
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
        expect(root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Удалить пару 1"]')?.disabled).toBe(true);
        const before = component().drafts().MATCH.pairs.map(pair => pair.left.itemId + pair.right.itemId);
        for (let index = 0; index < 5; index++)
            root.querySelector<HTMLButtonElement>('[data-add-pair]')!.click();
        refresh();
        expect(component().drafts().MATCH.pairs.length).toBe(6);
        expect(root.querySelector<HTMLButtonElement>('[data-add-pair]')?.disabled).toBe(true);
        expect(component().drafts().MATCH.pairs.slice(0, 2).map(pair => pair.left.itemId + pair.right.itemId)).toEqual(before);
        root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Опустить пару 1"]')!.click();
        refresh();
        expect(component().drafts().MATCH.pairs[1].left.itemId + component().drafts().MATCH.pairs[1].right.itemId).toBe(before[0]);
        for (let index = 0; index < 4; index++)
            root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Удалить пару 1"]')!.click();
        refresh();
        expect(component().drafts().MATCH.pairs.length).toBe(2);
    });

    it('checks a pair of the author\'s match through the preview endpoint and shows the wrong-pair message', () => {
        configure(detailOf(mechanics['createMatchMixed'].exercise));
        previewApi.checkPair.mockReturnValue(of(false));
        const surface = preview()!;
        surface.querySelector<HTMLButtonElement>('button[data-side="left"]')!.click();
        refresh();
        surface.querySelector<HTMLButtonElement>('button[data-side="right"]')!.click();
        refresh();
        expect(previewApi.checkPair).toHaveBeenCalledTimes(1);
        expect(surface.textContent).toContain('Эта пара не подходит');
        expect(studyApi.checkPair).not.toHaveBeenCalled();
    });

    it('adds a slot block of each kind within the profile and keeps COMPACT slots to one text and one media', () => {
        configure();
        select('CHOICE');
        const slot = page().querySelector('app-choice-editor [data-option] app-exercise-slot-editor')!;
        const add = (label: string) => buttonByText(label, slot);
        expect(add('+ Текст').disabled).toBe(true);
        expect(add('+ Фрагмент материала').disabled).toBe(true);
        expect(slot.textContent).not.toContain('+ YouTube');
        expect(slot.textContent).toContain('Не больше одного текста и одного изображения, аудио или видео');
        expect(slot.textContent).toContain('до 300 знаков');

        const prompt = page().querySelector('#step-prompt app-exercise-slot-editor')!;
        expect(prompt.textContent).toContain('до 4000 знаков');
        expect(prompt.textContent).toContain('+ YouTube');
        buttonByText('+ Фрагмент материала', prompt).click();
        refresh();
        buttonByText('+ YouTube', prompt).click();
        refresh();
        const blocks = component().drafts().CHOICE.prompt;
        expect(blocks.map(block => block.kind)).toEqual(['TEXT', 'MATERIAL', 'YOUTUBE']);
        expect(blocks[1]).toEqual({ kind: 'MATERIAL', memberKey: item.memberKey, itemRevisionId: item.itemRevisionId, nodeId: '' });
        const youtube = prompt.querySelector<HTMLInputElement>('input[id$="youtube-2"]')!;
        youtube.value = 'https://youtu.be/dQw4w9WgXcQ';
        youtube.dispatchEvent(new Event('input'));
        refresh();
        expect(component().drafts().CHOICE.prompt[2]).toEqual({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: '' });
    });

    it('lets the author pick a fragment of the material only inside a slot, never rendering the whole material', () => {
        configure();
        select('SELF_CHECK');
        expect(page().textContent).not.toContain('Attention');
        buttonByText('+ Фрагмент материала', page().querySelector('#step-prompt')!).click();
        refresh();
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
        component().save();
        refresh();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['prompt']).toContain('4000');
        expect(page().querySelector('#exercise-errors')?.textContent).toContain('4000');
    });

    it('shows the AI switch disabled with its badge and reason and never sends ai-semantic', () => {
        configure(null, { ...CAPABILITIES_UNAVAILABLE, aiAssessment: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' } });
        select('FREE_RESPONSE');
        const root = page();
        const toggle = root.querySelector<HTMLInputElement>('app-free-response-editor input[role="switch"]')!;
        expect(toggle.disabled).toBe(true);
        expect(toggle.checked).toBe(false);
        expect(root.querySelector('app-free-response-editor .stamp')?.textContent?.trim()).toBe('ИИ');
        expect(root.textContent).toContain('Проверять смысл ответа с ИИ');
        expect(root.textContent).toContain('Проверка объяснений и формулировок по эталону. Пока недоступна.');
        expect(root.textContent).toContain('поставщик проверки не подключён');
        expect(root.textContent).toContain('Это не проверка смысла');
        toggle.click();
        refresh();
        expect(toggle.checked).toBe(false);

        component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Что такое инерция?')],
            answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'свойство тел' }], matchingMode: 'SOFT' } });
        component().save();
        const json = JSON.stringify(created().exercise);
        expect(json).not.toContain('ai-semantic');
        expect(json).not.toContain('rubric');
        expect(json).not.toContain('TEXT_OR_SPEECH');
        expect(created().exercise.evaluatorPolicy).toEqual({ id: 'deterministic-text', version: '1' });
        expect((created().exercise.answerKey as {
            matchingMode: string;
        }).matchingMode).toBe('SOFT');
    });

    it('opens the optional reference of a free response only when it holds content', () => {
        configure();
        select('FREE_RESPONSE');
        expect(page().querySelector<HTMLDetailsElement>('details.optional')?.open).toBe(false);
        component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, reference: [text('Подробный эталон')] });
        refresh();
        expect(page().querySelector<HTMLDetailsElement>('details.optional')?.open).toBe(true);
    });

    it('fails closed when capabilities cannot be read and for an exercise that needs an unavailable capability', () => {
        const ai = clone(mechanics['rejectedAiAssessment'].exercise) as ExerciseSpec;
        configure(detailOf(ai), 'error');
        expect(component().capabilities()).toEqual(CAPABILITIES_UNAVAILABLE);
        // An exercise already checked by AI can always be switched back to a deterministic one; it can never be switched on.
        expect(page().querySelector<HTMLInputElement>('input[role="switch"]')?.disabled).toBe(false);
        expect(page().querySelector<HTMLInputElement>('input[role="switch"]')?.checked).toBe(true);
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
        buttonByText('Добавить аудио', slots[1]).click();
        refresh();
        expect(slots[1].querySelector('app-native-media-upload')).not.toBeNull();
        expect(slots[0].querySelector('app-native-media-upload')).toBeNull();
        const before = JSON.stringify(component().drafts());
        buttonByText('Записать аудио', slots[1]).click();
        refresh();
        await fixture.whenStable();
        refresh();
        expect(getUserMedia).toHaveBeenCalledTimes(1);
        expect(slots[1].textContent).toContain('Доступ к микрофону запрещён');
        expect(JSON.stringify(component().drafts())).toBe(before);
        expect(component().drafts().MATCH.pairs[0].left.blocks).toEqual(typed);
        expect(component().dirty()).toBe(true);
    });

    it('inserts a chosen uploaded asset only into the slot whose picker produced it', () => {
        configure();
        select('MATCH');
        const slots = page().querySelectorAll('app-match-editor [data-pair] app-exercise-slot-editor');
        buttonByText('Добавить аудио', slots[2]).click();
        refresh();
        buttonByText('Добавить аудио', slots[0]).click();
        refresh();
        const upload = slots[2].querySelector('app-native-media-upload')!;
        const instance = fixture.debugElement.queryAll(el => el.nativeElement === upload)[0].componentInstance;
        const scrolled = vi.mocked(scroll).mock.calls.length;
        instance.chooseAsset.emit({ kind: 'audio', assetId: id('99') });
        refresh();
        const pairs = component().drafts().MATCH.pairs;
        expect(pairs[1].left.blocks.map(block => block.kind)).toEqual(['TEXT', 'AUDIO']);
        expect(pairs[1].left.blocks[1]).toEqual({ kind: 'AUDIO', assetId: id('99'), title: 'Аудио' });
        expect(pairs[0].left.blocks.map(block => block.kind)).toEqual(['TEXT']);
        expect(pairs[0].right.blocks.map(block => block.kind)).toEqual(['TEXT']);
        expect(pairs[1].right.blocks.map(block => block.kind)).toEqual(['TEXT']);
        // A background upload finishing is not an explicit step action: the page does not scroll.
        expect(vi.mocked(scroll).mock.calls.length).toBe(scrolled);
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
        component().save();
        refresh();
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
            expect(lastCall(api.update)[5] as unknown).toEqual({ operation: 'revise', objectiveId: objective.objectiveId,
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
            api.create.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'VERSION_CONFLICT' } })));
            component().save();
            refresh();
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
            api.create.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'CAPABILITY_UNAVAILABLE' } })));
            component().save();
            refresh();
            expect(component().phase()).toBe('rejected');
            expect(page().textContent).toContain('сейчас недоступна на сервере');
        });

        it('keeps the same command id when retrying after a network failure', () => {
            configure();
            fill();
            api.create.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
            component().save();
            const commandId = lastCall(api.create)[5];
            expect(component().phase()).toBe('error');
            expect(stepIds().length).toBe(3);
            api.create.mockReturnValue(of({ acknowledgement, replayed: true }));
            component().retry();
            expect(lastCall(api.create)[5]).toBe(commandId);
        });

        it('turns a contract rejection raised before the request into a visible message', () => {
            configure();
            fill();
            api.create.mockReturnValue(throwError(() => new AuthoringProtocolError('Invalid block.')));
            component().save();
            refresh();
            expect(component().phase()).toBe('rejected');
            expect(page().textContent).toContain('не прошло проверку формата');
        });

        it('does not create a second copy while a save is pending', () => {
            configure();
            fill();
            const pending = new Subject<never>();
            api.create.mockReturnValue(pending);
            component().save();
            component().save();
            component().submit();
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
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
        expect(component().canLeave()).toBe(true);
        component().setEnabled(false);
        expect(component().canLeave()).toBe(false);
        expect(confirm).toHaveBeenCalled();
        component().resetChanges();
        expect(component().enabled()).toBe(true);
        expect(component().dirty()).toBe(false);
        component().setEnabled(false);
        component().save();
        expect((lastCall(api.update)[6] as ExerciseSpec).enabled).toBe(false);
        component().deleteExercise();
        expect(api.delete).toHaveBeenCalledWith(deck.deckId, detail.exerciseId, deck.rowVersion);
    });

    it('typing is dirty, the trial of the preview is not', () => {
        configure();
        select('FREE_RESPONSE');
        preview()!.querySelector<HTMLButtonElement>('button[data-submit]')?.click();
        expect(component().dirty()).toBe(false);
        type('#free-response-prompt-text-0', 'x');
        expect(component().dirty()).toBe(true);
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
        expect(component().canLeave()).toBe(true);
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
            component().page.set({ ...component().page()!, nextCursor: 'next' });
            refresh();
            api.list.mockReturnValue(of({ ...emptyPage, total: 3, exercises: [summaryOf(other, id('61'))], nextCursor: null }));
            buttonByText('Показать ещё').click();
            refresh();
            expect(page().querySelectorAll('#existing-exercises li').length).toBe(3);
        });

        it('toggles another exercise through a normal revision and refreshes the deck pins without touching the open draft', () => {
            const other = { ...detailOf(mechanics['createSelfCheck'].exercise, 1), exerciseId: id('60'), exerciseRevisionId: id('62') };
            configure(null, CAPABILITIES_UNAVAILABLE, [other]);
            select('FREE_RESPONSE', false);
            type('#free-response-prompt-text-0', 'Мой вопрос');
            const checkbox = page().querySelector<HTMLInputElement>('#existing-exercises input[type="checkbox"]')!;
            checkbox.click();
            refresh();
            const args = lastCall(api.update);
            expect(args[1]).toBe(id('60'));
            expect(args[4]).toBe(id('62'));
            expect(args[5] as unknown).toEqual({ operation: 'reuse', objectiveId: objective.objectiveId, objectiveRevisionId: objective.objectiveRevisionId });
            expect((args[6] as ExerciseSpec).enabled).toBe(false);
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
            api.delete.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412 })));
            component().deleteListed(component().page()!.exercises[0]);
            refresh();
            expect(page().querySelector('#existing-exercises .notice')?.textContent).toContain('Колода изменилась');
            expect(component().listBusy()).toBeNull();
            api.delete.mockReturnValue(of(undefined));
            component().deleteListed(component().page()!.exercises[0]);
            refresh();
            expect(api.delete).toHaveBeenCalledWith(deck.deckId, id('60'), deck.rowVersion);
            expect(page().querySelector('#existing-exercises .notice')?.textContent).toContain('удалено');
            expect(component().drafts().FREE_RESPONSE.prompt).toEqual([text('Мой вопрос')]);
        });
    });

    describe('ORDER and CATEGORIZE', () => {
        const orderSpec = () => mechanics['createOrder'].exercise;
        const categorizeSpec = () => mechanics['createCategorize'].exercise;

        it('ORDER from the contract fixture: the authored order is exactly what is saved', () => {
            configure();
            select('ORDER');
            component().setOrder({ prompt: orderSpec().content.prompt, items: orderSpec().content.items });
            component().save();
            expect(created().exercise).toEqual(orderSpec());
            expect(parseExerciseSpec(created().exercise)).toEqual(orderSpec());
            expect(created().objective).toEqual({ operation: 'create', title: 'Восстановите порядок.' });
        });

        it('CATEGORIZE from the contract fixture: the groups and the assignments are exactly what is saved', () => {
            configure();
            select('CATEGORIZE');
            component().setCategorize({ prompt: categorizeSpec().content.prompt, categories: categorizeSpec().content.categories,
                items: categorizeSpec().content.items.map((entry: {
                    itemId: string;
                    blocks: AuthoringBlock[];
                }) => ({ ...entry,
                    categoryId: categorizeSpec().answerKey.assignments.find((assignment: {
                        itemId: string;
                    }) => assignment.itemId === entry.itemId).categoryId })) });
            component().save();
            expect(created().exercise).toEqual(categorizeSpec());
        });

        it('opens a saved ORDER with every step filled and the authored order', () => {
            configure(detailOf(orderSpec()));
            expect(component().drafts().ORDER.items.map(entry => entry.itemId)).toEqual(orderSpec().answerKey.sequence);
            expect(page().querySelectorAll('app-order-editor [data-item]').length).toBe(6);
        });

        it('opens a saved CATEGORIZE with the groups and the group of each item', () => {
            configure(detailOf(categorizeSpec()));
            expect(component().drafts().CATEGORIZE.categories.map(group => group.label)).toEqual(['Существительное', 'Глагол', 'Наречие']);
            expect(component().drafts().CATEGORIZE.items.map(entry => entry.categoryId)).toEqual(categorizeSpec().answerKey.assignments.map((assignment: {
                categoryId: string;
            }) => assignment.categoryId));
            expect(page().querySelectorAll('app-category-groups-editor [data-category]').length).toBe(3);
            expect(page().querySelectorAll('app-categorize-items-editor [data-item]').length).toBe(4);
        });

        it('reopens an ORDER whose content array is not in key order and saves it back with the key as the authored order', () => {
            const shuffled = clone(orderSpec());
            shuffled.content.items = [...shuffled.content.items].reverse();
            shuffled.answerKey.sequence = shuffled.content.items.map((entry: {
                itemId: string;
            }) => entry.itemId).reverse();
            configure(detailOf(shuffled));
            expect(component().drafts().ORDER.items.map(entry => entry.itemId)).toEqual(shuffled.answerKey.sequence);
            component().save();
            const saved = lastCall(api.update)[6] as ExerciseSpec;
            expect(saved.type === 'ORDER' && saved.answerKey.sequence).toEqual(shuffled.answerKey.sequence);
            expect(saved.type === 'ORDER' && saved.content.items.map(entry => entry.itemId)).toEqual(shuffled.answerKey.sequence);
        });

        it('shows a real, playable demo with local media for each, the exact tile texts and a badge', () => {
            configure();
            const tiles = [...page().querySelectorAll('label.tile')].map(tile => [tile.querySelector('.tile-title')!.textContent, tile.querySelector('.tile-description')!.textContent]);
            expect(tiles).toContainEqual(['Восстановить порядок', 'Разместите слова, этапы или фрагменты в правильной последовательности. Ученик получит их вперемешку.']);
            expect(tiles).toContainEqual(['Распределить по группам', 'Создайте категории и примеры для каждой. Ученик определит, к какой группе относится каждый элемент.']);
            select('ORDER', false);
            expect(mode()).toBe('DEMO');
            expect(preview()!.querySelector('.stamp')?.textContent?.trim()).toBe('Пример');
            expect(preview()!.querySelectorAll('li.order-item').length).toBe(4);
            expect(preview()!.querySelectorAll('audio').length).toBe(2);
            select('CATEGORIZE', false);
            expect(mode()).toBe('DEMO');
            expect(preview()!.querySelectorAll('.group').length).toBe(3);
            expect(preview()!.querySelectorAll('[data-pool] [data-item-id]').length).toBe(6);
            expect(component().drafts().CATEGORIZE.items.every(entry => entry.categoryId === null)).toBe(true);
        });

        it('plays only through the preview endpoint: an ORDER demo answer reaches previewApi, never the Study API', () => {
            configure();
            select('ORDER', false);
            preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.click();
            refresh();
            expect(previewApi.submit).toHaveBeenCalledTimes(1);
            const [exercise, submission] = lastCall(previewApi.submit);
            expect(exercise.type).toBe('ORDER');
            expect(submission.response.kind).toBe('ORDER');
            expect(studyApi.submit).not.toHaveBeenCalled();
            expect(studyApi.start).not.toHaveBeenCalled();
            expect(api.create).not.toHaveBeenCalled();
        });

        it('follows the steps: instruction, items with the helpers, save; instruction, groups, items, save', () => {
            configure();
            select('ORDER', false);
            expect(stepIds()).toEqual(['step-prompt']);
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-items']);
            expect(page().querySelector('#step-items summary')?.textContent).toContain('Разбить текст на части');
            expect(page().querySelector('[data-split-words]')?.textContent).toContain('Разбить на слова');
            expect(page().querySelector('[data-split-lines]')?.textContent).toContain('Разбить по строкам');
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-items']);
            expect(page().querySelector('#step-items .step-problem')?.textContent).toContain('Введите текст блока');
            type('#order-item-' + component().drafts().ORDER.items[0].itemId + '-text-0', 'Первый');
            type('#order-item-' + component().drafts().ORDER.items[1].itemId + '-text-0', 'Второй');
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-items', 'step-finish']);
            expect(mode()).toBe('AUTHOR_READY');

            select('CATEGORIZE', false);
            expect(stepIds()).toEqual(['step-prompt']);
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-groups']);
            moveOn();
            expect(page().querySelector('#step-groups .step-problem')?.textContent).toContain('Назовите группу');
            for (const group of component().drafts().CATEGORIZE.categories)
                type('#categorize-groups-label-' + group.categoryId, 'Группа ' + group.categoryId.slice(-2));
            moveOn();
            expect(stepIds()).toEqual(['step-prompt', 'step-groups', 'step-items']);
            moveOn();
            expect(page().querySelector('#step-items .step-problem')).not.toBeNull();
            expect(stepIds()).toEqual(['step-prompt', 'step-groups', 'step-items']);
        });

        it('switching away from items or groups asks first, keeps the draft on cancel and restores it on the way back', () => {
            configure();
            select('ORDER');
            component().setOrder({ prompt: [], items: [{ itemId: '0d000000-0000-4000-8000-0000000000a1', blocks: [text('Первый')] },
                    { itemId: '0d000000-0000-4000-8000-0000000000a2', blocks: [text('Второй')] }] });
            refresh();
            radio('CATEGORIZE').click();
            refresh();
            expect(page().querySelector('.switch-confirm')?.textContent).toContain('элементы и их порядок');
            expect(component().mechanic()).toBe('ORDER');
            buttonByText('Отмена', page().querySelector('.switch-confirm')!).click();
            refresh();
            expect(component().mechanic()).toBe('ORDER');
            expect(component().drafts().ORDER.items.length).toBe(2);
            radio('CATEGORIZE').click();
            refresh();
            buttonByText('Переключиться').click();
            refresh();
            expect(component().mechanic()).toBe('CATEGORIZE');
            expect(component().drafts().ORDER.items.length).toBe(2);
            // A group with a name and an assigned item is data the next mechanic would lose, too.
            const groups = [{ categoryId: 'ca000000-0000-4000-8000-0000000000a1', label: 'Первая' }, { categoryId: 'ca000000-0000-4000-8000-0000000000a2', label: 'Вторая' }];
            component().setCategorize({ prompt: [], categories: groups, items: [{ itemId: '9a000000-0000-4000-8000-0000000000a1', blocks: [text('x')], categoryId: groups[0].categoryId },
                    { itemId: '9a000000-0000-4000-8000-0000000000a2', blocks: [text('y')], categoryId: null }] });
            refresh();
            radio('MATCH').click();
            refresh();
            expect(page().querySelector('.switch-confirm')?.textContent).toContain('группы, элементы и их распределение');
            buttonByText('Отмена', page().querySelector('.switch-confirm')!).click();
            refresh();
            expect(component().drafts().CATEGORIZE.categories.length).toBe(2);
        });

        it('treats an assigned-only CATEGORIZE draft as authored data, but an untouched one as pristine', () => {
            configure();
            select('CATEGORIZE', false);
            expect(mode()).toBe('DEMO');
            const first = component().drafts().CATEGORIZE;
            component().setCategorize({ ...first, items: first.items.map((entry, index) => index === 0 ? { ...entry, categoryId: first.categories[0].categoryId } : entry) });
            refresh();
            expect(mode()).toBe('AUTHOR_DRAFT');
        });

        it('shows a neutral draft preview with placeholders and stays unanswerable until the draft validates', () => {
            configure();
            select('CATEGORIZE', false);
            component().setPrompt([text('Распределите слова.')]);
            refresh();
            expect(mode()).toBe('AUTHOR_DRAFT');
            expect(preview()!.querySelector('.blocked')?.textContent).toContain('Проверить ответ пока нельзя');
            expect(preview()!.textContent).toContain('Добавьте элемент пары');
            expect(preview()!.textContent).toContain('Название группы 1');
            expect(preview()!.querySelector<HTMLButtonElement>('button[data-submit]')!.disabled).toBe(true);
            expect(previewApi.submit).not.toHaveBeenCalled();
        });

        for (const [mechanic, reason] of [['ORDER', 'Введите текст блока'], ['CATEGORIZE', 'Назовите группу']] as const) {
            it(`never saves the ${mechanic} demo and refuses an unfinished one with the reason`, () => {
                configure();
                select(mechanic);
                component().save();
                refresh();
                expect(api.create).not.toHaveBeenCalled();
                expect(component().phase()).toBe('rejected');
                expect(page().querySelector('#exercise-errors')?.textContent).toContain(reason);
            });
        }
    });


    describe('«Новое» (AI-13)', () => {
        it('marks a newly saved exercise in the list with the text «Новое» and leaves the others alone', () => {
            const marked = detailOf(mechanics['createSelfCheck'].exercise);
            newMarks = new Set([marked.exerciseId]);
            configure(null, CAPABILITIES_UNAVAILABLE, [marked, { ...detailOf(mechanics['createSelfCheck'].exercise), exerciseId: id('61') }]);
            refresh();
            const rows = page().querySelectorAll('#existing-exercises li');
            expect(rows[0]!.querySelector('app-new-badge')?.textContent).toBe('Новое');
            expect(rows[1]!.querySelector('app-new-badge')).toBeNull();
        });

        it('clears the mark when an exercise is opened, and ignores a failure of it', () => {
            configure(detailOf(mechanics['createSelfCheck'].exercise));
            expect(api.clearNewMark).toHaveBeenCalledWith(deck.deckId, id('50'));
            expect(component().phase()).toBe('ready');
            TestBed.resetTestingModule();
            configure(detailOf(mechanics['createSelfCheck'].exercise));
            api.clearNewMark.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
            component().load();
            refresh();
            expect(component().phase()).toBe('ready');
        });

        it('does not clear anything for a new exercise', () => {
            configure(null);
            expect(api.clearNewMark).not.toHaveBeenCalled();
        });
    });

    describe('«Попросить Мнему…» (AI-16)', () => {
        const available: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null } };
        const ask = () => page().querySelector<HTMLElement>('app-ask-mnema');

        it('is one collapsed line above the sheet of a saved exercise when the server offers generation, about this exercise', () => {
            const detail = detailOf(mechanics['createSelfCheck'].exercise);
            configure(detail, available);
            refresh();
            expect(ask()).not.toBeNull();
            expect(ask()!.querySelector('.ask-trigger')?.textContent).toContain('Попросить Мнему…');
            expect(ask()!.querySelector('textarea')).toBeNull();
            expect(ask()!.compareDocumentPosition(page().querySelector('form.inspector')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
            expect(component().askContext()).toEqual({ kind: 'EXERCISE', exerciseId: detail.exerciseId });
            ask()!.querySelector<HTMLButtonElement>('.ask-trigger')!.click();
            refresh();
            expect(ask()!.textContent).toContain('Замени аудио на мужской голос');
            expect(ask()!.textContent).not.toContain('сохранённой версией');
            component().setEnabled(false);
            refresh();
            expect(ask()!.textContent).toContain('сохранённой версией');
        });

        it('is not offered for a new exercise, without the capability (or when it cannot be read), and not for a Workshop proposal', () => {
            configure(null, available);
            refresh();
            expect(ask()).toBeNull();
            TestBed.resetTestingModule();
            configure(detailOf(mechanics['createSelfCheck'].exercise), CAPABILITIES_UNAVAILABLE);
            refresh();
            expect(ask()).toBeNull();
            TestBed.resetTestingModule();
            configure(detailOf(mechanics['createSelfCheck'].exercise), 'error');
            refresh();
            expect(ask()).toBeNull();
        });
    });

    describe('editing a Workshop proposal («Изменить», AI-13)', () => {
        const sessionId = generationIds.sessionId;
        const artifactId = 'a7a70000-0000-4000-8000-000000000001';
        const proposalExercise = (): ExerciseSpec => clone(mechanics['createSelfCheck'].exercise) as ExerciseSpec;
        const artifact = (command: Record<string, unknown> = createCommand(proposalExercise(), 'Цель Мнемы'), overrides: Record<string, unknown> = {}) =>
            parseArtifactDetail(exerciseDetail(artifactId, 0, command, { sessionId, ...overrides },
                { mechanic: (command['exercise'] as ExerciseSpec).type, objectiveTitle: 'Цель Мнемы', quotes: {} }));
        const open = (found: ArtifactDetail | Observable<ArtifactDetail> = artifact(), others: readonly ExerciseDetail[] = []) => {
            TestBed.resetTestingModule();
            extraQuery = { session: sessionId, artifact: artifactId };
            proposalAnswer = found instanceof Observable ? found : of(found);
            configure(null, CAPABILITIES_UNAVAILABLE, others);
            refresh();
        };
        const approved = () => generationApi.approveArtifact.mockImplementation((_d, _s, target, _pin, commandId) =>
            of(parseApprovalAck(ack(commandId, [target.artifactId]), false)));

        it('opens the proposal in the editor: its mechanic, its content, the way back to the Workshop and a save that says what it does', () => {
            open();
            expect(generationApi.getArtifact).toHaveBeenCalledWith(deck.deckId, sessionId, artifactId);
            expect(component().mechanic()).toBe('SELF_CHECK');
            expect(component().proposalEdit()).not.toBeNull();
            expect(component().phase()).toBe('ready');
            expect(page().querySelector('.eyebrow')?.textContent).toBe('Правка упражнения Мнемы');
            expect(page().querySelector('.lede')?.textContent).toContain('предложила Мнема');
            expect(page().querySelector('a[data-back-workshop]')).not.toBeNull();
            expect(component().workshopLink()).toEqual(['/decks', deck.deckId, 'workshop', sessionId]);
            expect(buttonByText('Сохранить в колоду')).toBeDefined();
            expect(page().querySelector('[data-create-another]')).toBeNull();
            expect(component().dirty()).toBe(false);
            expect(component().objectiveMode()).toBe('create');
            expect(component().effectiveTitle()).toBe('Цель Мнемы');
            expect(page().querySelector('app-exercise-preview-host')).not.toBeNull();
        });

        it('saves by approving the artifact with the edited exercise as the replacement, then returns to the Workshop', () => {
            open();
            approved();
            component().setPrompt([text('Исправленный вопрос?')]);
            refresh();
            component().save();
            refresh();
            expect(api.create).not.toHaveBeenCalled();
            expect(api.update).not.toHaveBeenCalled();
            const [deckId, session, target, pin, commandId, replacement] = lastCall(generationApi.approveArtifact);
            expect(deckId).toBe(deck.deckId);
            expect(session).toBe(sessionId);
            expect(target).toEqual({ artifactId, expectedArtifactVersion: '3', expectedRevisionId: component().proposalEdit()!.artifact.currentRevisionId });
            expect(pin).toEqual({ rowVersion: deck.rowVersion, revisionId: deck.revisionId });
            expect(commandId).toMatch(/^[0-9a-f-]{36}$/);
            expect(replacement!.objective).toEqual({ operation: 'create', title: 'Цель Мнемы' });
            expect(JSON.stringify(replacement!.exercise)).toContain('Исправленный вопрос?');
            expect(replacement!.exercise.subject).toEqual({ memberKey: item.memberKey, itemRevisionId: item.itemRevisionId });
            expect(toast.echo).toHaveBeenCalledWith('Упражнение сохранено в колоду');
            expect(router.navigate).toHaveBeenCalledWith(['/decks', deck.deckId, 'workshop', sessionId]);
            expect(component().dirty()).toBe(false);
        });

        it('keeps the objective the proposal reused when the page lists it, and creates one by title when it does not', () => {
            const listed = detailOf(mechanics['createSelfCheck'].exercise);
            open(artifact(createCommand(proposalExercise()), {}), [listed]);
            expect(component().objectiveMode()).toBe('create');
            const reuse = { objective: { operation: 'reuse', objectiveId: listed.objective.objectiveId, objectiveRevisionId: listed.objective.objectiveRevisionId }, exercise: proposalExercise() };
            open(artifact(reuse), [listed]);
            expect(component().objectiveMode()).toBe('reuse');
            expect(component().selectedObjectiveId()).toBe(listed.objective.objectiveId);
            const unknown = { objective: { operation: 'reuse', objectiveId: id('77'), objectiveRevisionId: id('78') }, exercise: proposalExercise() };
            open(artifact(unknown));
            expect(component().objectiveMode()).toBe('create');
            expect(component().effectiveTitle()).toBe('Цель Мнемы');
        });

        it('quotes the earlier revision of the material the proposal was written from when the page has moved on', () => {
            const exercise = proposalExercise();
            const quoted = { ...exercise, content: { ...(exercise as any).content, reference: [{ kind: 'MATERIAL', memberKey: item.memberKey, itemRevisionId: item.itemRevisionId,
                nodeId: '00000000-0000-4000-8000-0000000000aa' }] } } as ExerciseSpec;
            open(parseArtifactDetail(exerciseDetail(artifactId, 0, createCommand(quoted), { sessionId },
                { mechanic: 'SELF_CHECK', objectiveTitle: 'x', quotes: { '00000000-0000-4000-8000-0000000000aa': 'Старая формулировка' } })));
            expect(component().projections().some(projection => projection.text === 'Старая формулировка')).toBe(true);
            expect(component().projections().length).toBe(4);
        });

        it('sends the very same command again after an answer that never came', () => {
            open();
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
            approved();
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
            component().save();
            refresh();
            expect(component().phase()).toBe('error');
            expect(component().message()).toContain('та же команда');
            expect(page().querySelector('a[data-back-workshop]')).not.toBeNull();
            const first = lastCall(generationApi.approveArtifact)[4];
            component().retry();
            refresh();
            expect(generationApi.approveArtifact.mock.calls.at(-1)![4]).toBe(first);
        });

        it('explains a stale proposal in the editor\'s words, keeps the edits and offers the way back instead of reloading', () => {
            open();
            component().setPrompt([text('Моя правка?')]);
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => problemResponse(409, { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE' })));
            component().save();
            refresh();
            expect(component().phase()).toBe('conflict');
            expect(component().message()).toContain('Материал изменился — предложение устарело');
            expect(component().message()).toContain('правки остались в форме');
            expect(page().querySelector('a[data-back-workshop]')).not.toBeNull();
            // «Загрузить свежую основу» would throw the edits away: it is not offered for a proposal.
            expect(page().textContent).not.toContain('Загрузить свежую основу');
            expect(JSON.stringify(component().drafts())).toContain('Моя правка?');
        });

        it('on a 412 reads only the pins again and sends the same edit once more as a new command: the edits are never discarded', () => {
            open();
            component().setPrompt([text('Моя правка?')]);
            approved();
            generationApi.approveArtifact.mockReset();
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => problemResponse(412)));
            generationApi.approveArtifact.mockImplementationOnce((_d, _s, target, _pin, commandId) => of(parseApprovalAck(ack(commandId, [target.artifactId]), false)));
            const moved = { ...deck, rowVersion: '9', revisionId: id('88') };
            decksApi.detail.mockReturnValue(of(moved));
            generationApi.getArtifact.mockClear();
            component().save();
            refresh();
            expect(generationApi.approveArtifact).toHaveBeenCalledTimes(2);
            const [first, second] = generationApi.approveArtifact.mock.calls;
            expect(first![3]).toEqual({ rowVersion: deck.rowVersion, revisionId: deck.revisionId });
            expect(second![3]).toEqual({ rowVersion: '9', revisionId: id('88') });
            expect(second![4]).not.toBe(first![4]);
            expect(JSON.stringify(second![5]!.exercise)).toContain('Моя правка?');
            expect(generationApi.getArtifact).toHaveBeenCalledTimes(1);
            expect(router.navigate).toHaveBeenCalledWith(['/decks', deck.deckId, 'workshop', sessionId]);
            expect(component().deck()!.rowVersion).toBe('9');
        });

        it('gives up after one retry, and when the proposal was decided meanwhile, with the edits still in the form', () => {
            open();
            component().setPrompt([text('Моя правка?')]);
            generationApi.approveArtifact.mockReturnValue(throwError(() => problemResponse(412)));
            component().save();
            refresh();
            expect(generationApi.approveArtifact).toHaveBeenCalledTimes(2);
            expect(component().phase()).toBe('conflict');
            expect(component().message()).toContain('Ваши правки остались в форме');
            expect(JSON.stringify(component().drafts())).toContain('Моя правка?');
            generationApi.approveArtifact.mockClear();
            generationApi.getArtifact.mockReturnValue(of(artifact(undefined, { state: 'PUBLISHED' })));
            component().save();
            refresh();
            expect(generationApi.approveArtifact).toHaveBeenCalledTimes(1);
            expect(component().message()).toContain('уже решено');
            expect(JSON.stringify(component().drafts())).toContain('Моя правка?');
        });

        it('says so when the pins cannot be read again, and explains a refusal of the exercise in words', () => {
            open();
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => problemResponse(412)));
            generationApi.getArtifact.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            component().save();
            refresh();
            expect(component().phase()).toBe('error');
            expect(component().message()).toContain('правки остались в форме');
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => problemResponse(400, { code: 'INVALID_REQUEST' })));
            component().save();
            refresh();
            expect(component().phase()).toBe('rejected');
            expect(component().message()).toContain('Упражнение не принято');
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => problemResponse(404)));
            component().save();
            refresh();
            expect(component().message()).toContain('больше недоступны');
        });

        it('does not call a request the client refused to build an unknown outcome', () => {
            open();
            generationApi.approveArtifact.mockReturnValueOnce(throwError(() => new RequestValidationError('Invalid exercise.')));
            component().save();
            refresh();
            expect(component().phase()).toBe('rejected');
            expect(component().message()).toContain('Упражнение не принято');
            expect(component().message()).not.toContain('та же команда');
        });

        it('does not send an exercise that does not validate', () => {
            open();
            component().setPrompt([text('')]);
            refresh();
            component().save();
            refresh();
            expect(generationApi.approveArtifact).not.toHaveBeenCalled();
            expect(component().phase()).toBe('rejected');
        });

        it('refuses a proposal that is already decided, belongs to another material, or cannot be read, and offers the way back', () => {
            open(artifact(undefined, { state: 'PUBLISHED' }));
            expect(component().proposalEdit()).toBeNull();
            expect(component().phase()).toBe('error');
            expect(component().message()).toContain('нельзя править');
            expect(page().querySelector('a[data-back-workshop]')).not.toBeNull();
            const other = { ...proposalExercise(), subject: { memberKey: id('99'), itemRevisionId: item.itemRevisionId } } as ExerciseSpec;
            open(artifact(createCommand(other)));
            expect(component().message()).toContain('нельзя править');
            open(parseArtifactDetail(exerciseDetail(artifactId, 0, { objective: { operation: 'create', title: 'x' }, exercise: { type: 'NOPE' } }, { sessionId },
                { mechanic: 'CHOICE', objectiveTitle: 'x', quotes: {} })));
            expect(component().message()).toContain('нельзя править');
        });

        it('says so when the proposal cannot be loaded at all', () => {
            open(throwError(() => new HttpErrorResponse({ status: 500 })));
            expect(component().message()).toBe('Не удалось загрузить упражнение из мастерской.');
            expect(page().querySelector('a[data-back-workshop]')).not.toBeNull();
        });

        it('has no way back to a Workshop when the address names none', () => {
            configure(null);
            expect(component().workshopLink()).toBeNull();
        });
    });

    describe('AI rubric (rubric v1)', () => {
        const AVAILABLE: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiAssessment: { available: true, reason: null } };
        const toggle = () => page().querySelector<HTMLInputElement>('app-free-response-editor input[role="switch"]')!;
        const rubricEditor = () => page().querySelector('app-ai-rubric-editor');
        const points = () => [...page().querySelectorAll<HTMLElement>('app-ai-rubric-editor li[data-criterion]')];
        const field = (selector: string) => page().querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
        const edit = (element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string, event = 'input') => {
            element.value = value;
            element.dispatchEvent(new Event(event));
            refresh();
        };

        function fill(): void {
            component().setPrompt([text('Объясните, что такое инерция.')]);
            refresh();
            edit(field('#free-response-rubric-reference'), 'Инерция — свойство тела сохранять скорость.');
            const descriptions = points().map(point => point.querySelector<HTMLTextAreaElement>('textarea')!);
            ['Говорит о сохранении скорости', 'Называет условие: нет воздействия', 'Приводит пример'].forEach((value, index) => edit(descriptions[index], value));
        }

        it('enables the switch only when the capability is available and then shows the rubric editor instead of the answer list', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            expect(toggle().disabled).toBe(false);
            expect(toggle().checked).toBe(false);
            expect(page().textContent).toContain('сравнит ответ с эталоном');
            expect(page().querySelector('app-text-answer-editor')).not.toBeNull();
            expect(rubricEditor()).toBeNull();

            toggle().click();
            refresh();
            expect(toggle().checked).toBe(true);
            expect(rubricEditor()).not.toBeNull();
            expect(page().querySelector('app-text-answer-editor')).toBeNull();
            expect(points().length).toBe(3);
            expect(page().querySelector('#step-answers-title')?.textContent).toContain('Эталон и пункты проверки');
            expect(page().querySelector('#free-response-rubric-reference')).not.toBeNull();
        });

        it('keeps the rubric when the switch is turned off and on again, and returns to the deterministic list', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            toggle().click();
            refresh();
            edit(field('#free-response-rubric-reference'), 'Мой эталон');
            toggle().click();
            refresh();
            expect(rubricEditor()).toBeNull();
            expect(page().querySelector('app-text-answer-editor')).not.toBeNull();
            expect(component().drafts().FREE_RESPONSE.aiRubric).toBeNull();
            toggle().click();
            refresh();
            expect(field('#free-response-rubric-reference').value).toBe('Мой эталон');
        });

        it('publishes an exact rubric v1 with a derived answer key and the criteria in the authored order', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            toggle().click();
            refresh();
            fill();
            const second = points()[1];
            edit(second.querySelector<HTMLSelectElement>('select[id$="-weight"]')!, '2', 'change');
            buttonByText('+ Добавить пункт').click();
            refresh();
            const added = points()[3];
            edit(added.querySelector<HTMLTextAreaElement>('textarea')!, 'Называет слово «инерция»');
            edit(added.querySelector<HTMLSelectElement>('select[id$="-tier"]')!, 'TERM', 'change');
            const details = page().querySelector<HTMLDetailsElement>('app-ai-rubric-editor details.fine')!;
            expect(details.open).toBe(false);
            expect(details.textContent).toContain('Тонкая настройка');
            details.open = true;
            details.dispatchEvent(new Event('toggle'));
            refresh();
            buttonByText('+ Добавить ошибку').click();
            refresh();
            edit(page().querySelector<HTMLInputElement>('app-ai-rubric-editor details input[type="text"]')!, 'Скорость не сохраняется');
            buttonByText('+ Добавить термин').click();
            refresh();
            const inputs = page().querySelectorAll<HTMLInputElement>('app-ai-rubric-editor details input[type="text"]');
            edit(inputs[1], 'инертность');
            expect(details.open).toBe(true);
            // Removing the last row of a list never collapses the section around the focus.
            buttonByText('Убрать', page().querySelectorAll('app-rubric-text-list')[1]).click();
            buttonByText('Убрать', page().querySelectorAll('app-rubric-text-list')[1]).click();
            refresh();
            expect(details.open).toBe(true);
            buttonByText('+ Добавить термин').click();
            refresh();
            edit(page().querySelectorAll<HTMLInputElement>('app-ai-rubric-editor details input[type="text"]')[1], 'инертность');
            component().setObjectiveTitle('Инерция');
            component().save();
            refresh();
            const spec = created().exercise as Extract<ExerciseSpec, { type: 'FREE_RESPONSE' }>;
            expect(parseExerciseSpec(spec)).toEqual(spec);
            const policy = spec.evaluatorPolicy as { id: string; version: string; rubric: Record<string, unknown> };
            expect(policy.id).toBe('ai-semantic');
            expect(policy.version).toBe('1');
            expect(Object.keys(policy.rubric)).toEqual(['referenceAnswer', 'criteria', 'misconceptions', 'acceptableTerms']);
            const criteria = policy.rubric['criteria'] as { tier: string; weight: number; description: string; criterionId: string }[];
            expect(criteria.map(entry => [entry.tier, entry.weight, entry.description])).toEqual([
                ['CORE', 3, 'Говорит о сохранении скорости'], ['CORE', 2, 'Называет условие: нет воздействия'],
                ['DETAIL', 1, 'Приводит пример'], ['TERM', 1, 'Называет слово «инерция»']]);
            expect(new Set(criteria.map(entry => entry.criterionId)).size).toBe(4);
            expect(policy.rubric['misconceptions']).toEqual(['Скорость не сохраняется']);
            expect(policy.rubric['acceptableTerms']).toEqual(['инертность']);
            expect(spec.answerKey).toEqual({ kind: 'TEXT', accepted: ['Инерция — свойство тела сохранять скорость.'],
                normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' });
        });

        it('explains the 2-3 / 1-4 / 0-2 rule with live counters and blocks saving until it holds', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            toggle().click();
            refresh();
            const counts = () => page().querySelector('app-ai-rubric-editor .tier-counts')!.textContent!.replace(/\s+/g, ' ');
            expect(counts()).toContain('Суть: 2 (нужно 2–3)');
            expect(counts()).toContain('Детали: 1 (нужно 1–4)');
            expect(counts()).toContain('Термины: 0 (нужно 0–2)');
            expect(page().querySelector('app-ai-rubric-editor .tier-counts')?.getAttribute('role')).toBe('status');
            const detail = points()[2];
            edit(detail.querySelector<HTMLSelectElement>('select[id$="-tier"]')!, 'TERM', 'change');
            expect(counts()).toContain('Детали: 0 (нужно 1–4) · добавьте');
            expect(page().querySelector('app-ai-rubric-editor .tier-count.off')).not.toBeNull();
            fill();
            component().setObjectiveTitle('Инерция');
            component().save();
            refresh();
            expect(api.create).not.toHaveBeenCalled();
            expect(page().querySelector('app-ai-rubric-editor fieldset.points .field-error')?.textContent).toContain('«Детали» (0)');
            edit(points()[2].querySelector<HTMLSelectElement>('select[id$="-tier"]')!, 'DETAIL', 'change');
            component().save();
            refresh();
            expect(api.create).toHaveBeenCalledTimes(1);
        });

        it('flags an empty reference answer and an empty key point next to their fields', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            toggle().click();
            refresh();
            component().setPrompt([text('Вопрос')]);
            component().setObjectiveTitle('Цель');
            component().save();
            refresh();
            expect(api.create).not.toHaveBeenCalled();
            const reference = field('#free-response-rubric-reference');
            expect(reference.getAttribute('aria-invalid')).toBe('true');
            expect(page().querySelector(`#${reference.getAttribute('aria-describedby')!.split(' ').pop()}`)?.textContent).toContain('эталонный ответ');
            expect(points()[0].querySelector('.field-error')?.textContent).toContain('Опишите пункт');
            expect(points()[0].querySelector('textarea')?.getAttribute('aria-invalid')).toBe('true');
        });

        it('announces a refused save once: one summary line per distinct message, and no second alert on the rubric fields', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            toggle().click();
            refresh();
            component().setPrompt([text('Вопрос')]);
            component().setObjectiveTitle('Цель');
            component().save();
            refresh();
            const lines = [...page().querySelectorAll('#exercise-errors li')].map(item => item.textContent?.trim());
            expect(lines.filter(line => line === 'Опишите пункт или удалите его.').length).toBe(1);
            expect(new Set(lines).size).toBe(lines.length);
            expect(page().querySelector('#exercise-errors')?.getAttribute('role')).toBe('alert');
            expect(page().querySelectorAll('app-ai-rubric-editor [role="alert"]').length).toBe(0);
            expect(page().querySelectorAll('app-ai-rubric-editor .field-error').length).toBeGreaterThan(3);
        });

        it('announces a refused «Продолжить» once, in a single alert with distinct lines', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE', false);
            component().setPrompt([text('Вопрос')]);
            refresh();
            moveOn();
            toggle().click();
            refresh();
            moveOn();
            const alert = page().querySelector('#step-answers [role="alert"]')!;
            const lines = [...alert.querySelectorAll('.step-problem')].map(item => item.textContent?.trim());
            expect(new Set(lines).size).toBe(lines.length);
            expect(page().querySelectorAll('app-ai-rubric-editor [role="alert"]').length).toBe(0);
        });

        it('names the controls of a key point with their visible text: «Вид пункта», «Вес пункта», «Убрать»', () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            toggle().click();
            refresh();
            const point = points()[0];
            const labels = [...point.querySelectorAll('label')].map(label => label.textContent?.replace(/\s+/g, ' ').trim());
            expect(labels).toContain('Вид пункта 1');
            expect(labels).toContain('Вес пункта 1');
            const remove = point.querySelector('button')!;
            expect(remove.textContent?.trim()).toBe('Убрать');
            expect(remove.getAttribute('aria-label')).toContain('Убрать');
            expect(point.querySelector('select[id$="-tier"]')?.id).toBeTruthy();
            expect(point.querySelector(`label[for="${point.querySelector('select[id$="-tier"]')!.id}"]`)).not.toBeNull();
        });

        it('moves focus to the new key point on add and to the neighbour on remove, and never allows more than nine points', async () => {
            configure(null, AVAILABLE);
            select('FREE_RESPONSE');
            toggle().click();
            refresh();
            buttonByText('+ Добавить пункт').click();
            refresh();
            await fixture.whenStable();
            expect(points().length).toBe(4);
            expect(document.activeElement).toBe(points()[3].querySelector('textarea'));
            (points()[1].querySelector('button[aria-label="Убрать пункт 2"]') as HTMLButtonElement).click();
            refresh();
            await fixture.whenStable();
            expect(points().length).toBe(3);
            expect(document.activeElement).toBe(points()[1].querySelector('textarea'));
            while (points().length < 9) { buttonByText('+ Добавить пункт').click(); refresh(); }
            expect(buttonByText('+ Добавить пункт').disabled).toBe(true);
        });

        it('loads an existing rubric into the editor, keeps its answer key and round-trips it', () => {
            const ai = clone(mechanics['rejectedAiAssessment'].exercise) as ExerciseSpec;
            configure(detailOf(ai), AVAILABLE);
            expect(toggle().checked).toBe(true);
            expect(toggle().disabled).toBe(false);
            expect(points().length).toBe(4);
            expect(field('#free-response-rubric-reference').value).toContain('Инерция');
            // The fixture carries typical mistakes and terms, so the fine settings open once, for what is already inside.
            expect(page().querySelector<HTMLDetailsElement>('app-ai-rubric-editor details.fine')?.open).toBe(true);
            component().save();
            refresh();
            expect(lastCall(api.update)[6]).toEqual(ai);
        });
    });

    // Horizontal overflow with long strings at 320/390 in every mechanic is geometry jsdom cannot measure; the browser
    // harness owns it (scripts/browser-identity, scenario mechanics_editor_reflow).
});

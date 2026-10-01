import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AuthoringBlock, ExerciseSpec, Mechanic } from '../../content/exercise/exercise-content.models';
import { parseExerciseSpec } from '../../content/exercise/exercise-content.parse';
import { NativeDocument } from '../../content/native-document';
import { MEDIA_PLAYBACK_RESOLVER, MediaPlaybackResolver } from '../study/media-playback-resolver';
import { clone, fakePlayback, mechanics, removedNames } from '../study/study-test-data';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringProtocolError } from './authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from './capabilities-api.service';
import { ExerciseApiService } from './exercise-api.service';
import { ExerciseAuthoringPageComponent } from './exercise-authoring-page.component';
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
    let capabilityApi: jasmine.SpyObj<CapabilitiesApiService>;
    let router: jasmine.SpyObj<Router>;
    let fixture: ComponentFixture<ExerciseAuthoringPageComponent>;
    let getUserMedia: jasmine.Spy;

    function detailOf(spec: ExerciseSpec): ExerciseDetail {
        return { exerciseId: id('50'), exerciseRevisionId: id('51'), exerciseVersion: '1', ordinal: 0,
            createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', objective,
            deckId: deck.deckId, deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion, ...spec };
    }

    function configure(detail: ExerciseDetail | null = null, capabilities: LearningCapabilities | 'error' = CAPABILITIES_UNAVAILABLE): void {
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        const items = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['read']);
        api = jasmine.createSpyObj<ExerciseApiService>('ExerciseApiService', ['list', 'read', 'create', 'update', 'delete']);
        capabilityApi = jasmine.createSpyObj<CapabilitiesApiService>('CapabilitiesApiService', ['read']);
        router = jasmine.createSpyObj<Router>('Router', ['navigate']);
        decks.detail.and.returnValue(of(deck)); items.read.and.returnValue(of(item));
        const summary = detail === null ? [] : [{ exerciseId: detail.exerciseId, exerciseRevisionId: detail.exerciseRevisionId,
            exerciseVersion: '1', ordinal: 0, type: detail.type, enabled: detail.enabled, schemaVersion: 2 as const,
            createdAt: detail.createdAt, updatedAt: detail.updatedAt, objective: detail.objective }];
        api.list.and.returnValue(of({ ...emptyPage, total: summary.length, exercises: summary }));
        api.read.and.returnValue(of(detail ?? detailOf(mechanics['createSelfCheck'].exercise)));
        api.create.and.returnValue(of({ acknowledgement, replayed: false }));
        api.update.and.returnValue(of({ acknowledgement, replayed: false }));
        api.delete.and.returnValue(of(undefined));
        capabilityApi.read.and.returnValue(capabilities === 'error' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(capabilities));
        router.navigate.and.resolveTo(true);
        const playback = jasmine.createSpyObj<MediaPlaybackResolver>('MediaPlaybackResolver', ['resolve']);
        playback.resolve.and.callFake(fakePlayback);
        const upload = jasmine.createSpyObj<NativeMediaUploadApi>('NativeMediaUploadApi',
            ['policy', 'intent', 'status', 'partUrls', 'completedParts', 'finalize', 'retry', 'cancel', 'renewSingle', 'put']);
        getUserMedia = spyOn(navigator.mediaDevices, 'getUserMedia').and.callFake(() =>
            Promise.reject(new DOMException('denied', 'NotAllowedError')));
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: {
                paramMap: convertToParamMap({ deckId: deck.deckId, ...(detail ? { exerciseId: detail.exerciseId } : { memberKey: item.memberKey }) }),
                queryParamMap: convertToParamMap({})
            } } },
            { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decks },
            { provide: ItemApiService, useValue: items }, { provide: ExerciseApiService, useValue: api },
            { provide: CapabilitiesApiService, useValue: capabilityApi }, { provide: MEDIA_PLAYBACK_RESOLVER, useValue: playback },
            { provide: NativeMediaUploadApi, useValue: upload }
        ] });
        fixture = TestBed.createComponent(ExerciseAuthoringPageComponent);
        fixture.detectChanges();
    }

    const page = () => fixture.nativeElement as HTMLElement;
    const component = () => fixture.componentInstance;
    const text = (value: string): AuthoringBlock => ({ kind: 'TEXT', text: value });

    function buttonByText(label: string, root: ParentNode = page()): HTMLButtonElement {
        const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === label);
        if (button === undefined) throw new Error(`Missing button ${label}`);
        return button;
    }

    function created(): { objective: Record<string, unknown>; exercise: ExerciseSpec } {
        const args = api.create.calls.mostRecent().args;
        return { objective: args[3] as unknown as Record<string, unknown>, exercise: args[4] };
    }

    it('starts without any microphone request and shows five mechanics, never legacy types', () => {
        configure();
        expect(getUserMedia).not.toHaveBeenCalled();
        const names = [...page().querySelectorAll('.choice-card strong')].map(node => node.textContent);
        expect(names).toEqual(['Вспомнить и сверить', 'Ввести ответ', 'Заполнить пропуски', 'Выбрать ответ', 'Соединить пары']);
        for (const mechanic of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH']) {
            expect(page().querySelector(`input[name="mechanic"][value="${mechanic}"]`)).not.toBeNull();
        }
        for (const legacy of removedNames) {
            expect(page().innerHTML).not.toContain(legacy);
        }
        // Switching mechanics must not ask for the microphone either.
        component().setType('MATCH'); fixture.detectChanges();
        component().setType('SELF_CHECK'); fixture.detectChanges();
        expect(getUserMedia).not.toHaveBeenCalled();
    });

    describe('creates every mechanic', () => {
        it('SELF_CHECK with an image question and a rich reference', () => {
            configure();
            component().setType('SELF_CHECK');
            const fixtureSpec = mechanics['createSelfCheck'].exercise;
            component().setSelfCheck({ prompt: fixtureSpec.content.prompt, reference: fixtureSpec.content.reference });
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
            component().setType('FREE_RESPONSE');
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
            component().setType('CHOICE');
            component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
                correctIds: spec.answerKey.correctOptionIds });
            component().save();
            expect(created().exercise).toEqual(spec);
        });

        it('MATCH with text, audio, image, video and material items on either side', () => {
            configure();
            const spec = mechanics['createMatchMixed'].exercise;
            component().setType('MATCH');
            component().setMatch({ prompt: spec.content.prompt, pairs: spec.content.left.map((left: { itemId: string; blocks: AuthoringBlock[] }, index: number) => ({
                pairId: `pair-${index}`, left, right: spec.content.right[index] })) });
            component().save();
            expect(created().exercise).toEqual(spec);
            expect(parseExerciseSpec(created().exercise)).toEqual(spec);
        });
    });

    describe('reopens and saves every mechanic without changing it', () => {
        const cases: Array<[string, string]> = [['SELF_CHECK', 'createSelfCheck'], ['FREE_RESPONSE', 'createFreeResponseAudio'],
            ['CLOZE', 'createCloze'], ['CHOICE', 'createChoiceVideoMultiple'], ['MATCH', 'createMatchMixed']];
        for (const [type, name] of cases) {
            it(`${type}`, () => {
                const spec: ExerciseSpec = mechanics[name].exercise;
                configure(detailOf(spec));
                expect(component().type()).toBe(type as Mechanic);
                expect(component().objectiveMode()).toBe('reuse');
                component().save();
                expect(api.update.calls.mostRecent().args[6]).toEqual(spec);
                expect(api.update.calls.mostRecent().args[4]).toBe(id('51'));
            });
        }
    });

    it('keeps correct marks when switching multiple to single and reports a fix-required error', () => {
        configure();
        component().setType('CHOICE'); fixture.detectChanges();
        const spec = mechanics['createChoiceVideoMultiple'].exercise;
        component().setChoice({ prompt: spec.content.prompt, selectionMode: 'MULTIPLE', options: spec.content.options,
            correctIds: spec.answerKey.correctOptionIds });
        fixture.detectChanges();
        expect(page().querySelector('[id$="selection-error"]')).toBeNull();

        page().querySelector<HTMLInputElement>('app-choice-editor input[type="radio"]:not(:checked)')!.click();
        fixture.detectChanges();
        expect(component().drafts().CHOICE.selectionMode).toBe('SINGLE');
        expect(component().drafts().CHOICE.correctIds).toEqual(spec.answerKey.correctOptionIds);
        const error = page().querySelector('[id$="selection-error"]');
        expect(error?.textContent).toContain('только один вариант');
        expect(error?.getAttribute('role')).toBe('alert');

        component().save(); fixture.detectChanges();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['selection']).toContain('Снимите лишние отметки');
        expect(component().phase()).toBe('rejected');

        // Unchecking one mark fixes it without losing the other one.
        page().querySelectorAll<HTMLInputElement>('app-choice-editor .card > .check-line input[type="checkbox"]')[0].click(); fixture.detectChanges();
        expect(component().drafts().CHOICE.correctIds).toEqual([spec.answerKey.correctOptionIds[1]]);
        expect(page().querySelector('[id$="selection-error"]')).toBeNull();
    });

    it('marks, adds, reorders and removes choice options within 2 to 12', () => {
        configure();
        component().setType('CHOICE'); fixture.detectChanges();
        const root = page();
        expect(root.querySelectorAll('app-choice-editor [data-option]').length).toBe(2);
        expect(root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Удалить вариант 1"]')?.disabled).toBeTrue();
        for (let index = 0; index < 10; index++) root.querySelector<HTMLButtonElement>('[data-add-option]')!.click();
        fixture.detectChanges();
        expect(root.querySelectorAll('app-choice-editor [data-option]').length).toBe(12);
        expect(root.querySelector<HTMLButtonElement>('[data-add-option]')?.disabled).toBeTrue();
        const first = component().drafts().CHOICE.options[0].optionId;
        root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Опустить вариант 1"]')!.click(); fixture.detectChanges();
        expect(component().drafts().CHOICE.options[1].optionId).toBe(first);
        root.querySelector<HTMLInputElement>('app-choice-editor .card > .check-line input[type="checkbox"]')!.click(); fixture.detectChanges();
        expect(component().drafts().CHOICE.correctIds.length).toBe(1);
        root.querySelector<HTMLButtonElement>('app-choice-editor button[aria-label="Удалить вариант 1"]')!.click(); fixture.detectChanges();
        expect(component().drafts().CHOICE.options.length).toBe(11);
        expect(component().drafts().CHOICE.correctIds.length).toBe(0);
    });

    describe('cloze passage editing', () => {
        function selectIn(index: number, start: number, end: number): void {
            const area = page().querySelector<HTMLTextAreaElement>(`#cloze-text-${index}`)!;
            area.focus(); area.setSelectionRange(start, end);
            area.dispatchEvent(new Event('select')); fixture.detectChanges();
        }
        function typeIn(index: number, value: string): void {
            const area = page().querySelector<HTMLTextAreaElement>(`#cloze-text-${index}`)!;
            area.value = value; area.dispatchEvent(new Event('input')); fixture.detectChanges();
        }

        it('adds, edits and deletes blanks; repeated words get their own blank ids and nothing is lost', () => {
            configure();
            component().setType('CLOZE'); fixture.detectChanges();
            typeIn(0, 'map and map\n    .toList()');
            selectIn(0, 8, 11);
            buttonByText('Сделать пропуском').click(); fixture.detectChanges();
            let cloze = component().drafts().CLOZE;
            expect(cloze.texts).toEqual(['map and ', '\n    .toList()']);
            expect(cloze.blanks.length).toBe(1);
            expect(cloze.blanks[0].answer.rows[0].value).toBe('map');

            selectIn(0, 0, 3);
            buttonByText('Сделать пропуском').click(); fixture.detectChanges();
            cloze = component().drafts().CLOZE;
            expect(cloze.texts).toEqual(['', ' and ', '\n    .toList()']);
            expect(cloze.blanks.map(blank => blank.answer.rows[0].value)).toEqual(['map', 'map']);
            expect(new Set(cloze.blanks.map(blank => blank.blankId)).size).toBe(2);

            // Per-blank settings: fixed width and the first-letter hint on the second blank only.
            const cards = page().querySelectorAll('[data-blank]');
            expect(cards.length).toBe(2);
            const length = cards[1].querySelector<HTMLInputElement>('input[type="number"]')!;
            length.value = '8'; length.dispatchEvent(new Event('input'));
            [...cards[1].querySelectorAll<HTMLLabelElement>('label.check-line')]
                .find(label => label.textContent?.includes('Первая буква'))!.querySelector('input')!.click();
            fixture.detectChanges();
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

            // Deleting a blank returns its text, so no authored text is lost.
            buttonByText('Убрать пропуск и вернуть текст').click(); fixture.detectChanges();
            cloze = component().drafts().CLOZE;
            expect(cloze.blanks.length).toBe(1);
            expect(cloze.texts).toEqual(['map and ', '\n    .toList()']);
        });

        it('refuses to save without a blank or when answer lengths do not match the answer-length size', () => {
            configure();
            component().setType('CLOZE');
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
    });

    it('edits MATCH between 2 and 6 pairs and keeps every item id stable', () => {
        configure();
        component().setType('MATCH'); fixture.detectChanges();
        const root = page();
        expect(root.querySelectorAll('app-match-editor [data-pair]').length).toBe(2);
        expect(root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Удалить пару 1"]')?.disabled).toBeTrue();
        const before = component().drafts().MATCH.pairs.map(pair => pair.left.itemId + pair.right.itemId);
        for (let index = 0; index < 5; index++) root.querySelector<HTMLButtonElement>('[data-add-pair]')!.click();
        fixture.detectChanges();
        expect(component().drafts().MATCH.pairs.length).toBe(6);
        expect(root.querySelector<HTMLButtonElement>('[data-add-pair]')?.disabled).toBeTrue();
        expect(component().drafts().MATCH.pairs.slice(0, 2).map(pair => pair.left.itemId + pair.right.itemId)).toEqual(before);
        root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Опустить пару 1"]')!.click(); fixture.detectChanges();
        expect(component().drafts().MATCH.pairs[1].left.itemId + component().drafts().MATCH.pairs[1].right.itemId).toBe(before[0]);
        for (let index = 0; index < 4; index++) root.querySelector<HTMLButtonElement>('app-match-editor button[aria-label="Удалить пару 1"]')!.click();
        fixture.detectChanges();
        expect(component().drafts().MATCH.pairs.length).toBe(2);
    });

    it('adds a slot block of each kind within the profile and keeps COMPACT slots to one text and one media', () => {
        configure();
        component().setType('CHOICE'); fixture.detectChanges();
        const slot = page().querySelector('app-choice-editor [data-option] app-exercise-slot-editor')!;
        const add = (label: string) => buttonByText(label, slot);
        // A compact slot starts with one text block: no second text, no YouTube, one media block.
        expect(add('+ Текст').disabled).toBeTrue();
        expect(add('+ Фрагмент материала').disabled).toBeTrue();
        expect(slot.textContent).not.toContain('+ YouTube');
        expect(slot.textContent).toContain('Не больше одного текста и одного изображения, аудио или видео');
        expect(slot.textContent).toContain('до 300 знаков');

        const prompt = page().querySelector('app-choice-editor > app-exercise-slot-editor')!;
        expect(prompt.textContent).toContain('до 4000 знаков');
        expect(prompt.textContent).toContain('+ YouTube');
        buttonByText('+ Фрагмент материала', prompt).click(); fixture.detectChanges();
        buttonByText('+ YouTube', prompt).click(); fixture.detectChanges();
        const blocks = component().drafts().CHOICE.prompt;
        expect(blocks.map(block => block.kind)).toEqual(['TEXT', 'MATERIAL', 'YOUTUBE']);
        expect(blocks[1]).toEqual({ kind: 'MATERIAL', memberKey: item.memberKey, itemRevisionId: item.itemRevisionId, nodeId: '' });
        const youtube = prompt.querySelector<HTMLInputElement>('input[id$="youtube-2"]')!;
        youtube.value = 'https://youtu.be/dQw4w9WgXcQ'; youtube.dispatchEvent(new Event('input')); fixture.detectChanges();
        expect(component().drafts().CHOICE.prompt[2]).toEqual({ kind: 'YOUTUBE', videoId: 'dQw4w9WgXcQ', title: '' });
    });

    it('never truncates text: a block over its limit keeps all characters, shows the counter and blocks saving', () => {
        configure();
        component().setType('SELF_CHECK');
        const long = 'я'.repeat(4001);
        component().setSelfCheck({ prompt: [text(long)], reference: [text('ok')] });
        fixture.detectChanges();
        expect(page().querySelector('.counter.over')?.textContent).toContain('4001 / 4000');
        expect(component().drafts().SELF_CHECK.prompt[0]).toEqual(text(long));
        component().save(); fixture.detectChanges();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['prompt']).toContain('4000');
        expect(page().querySelector('#exercise-errors')?.textContent).toContain('4000');
    });

    it('shows the AI switch disabled with its badge and reason and never sends ai-semantic', () => {
        configure(null, { aiAssessment: { available: false, reason: 'PROVIDER_NOT_CONFIGURED' }, speechToText: { available: false, reason: 'DISABLED' } });
        const root = page();
        const toggle = root.querySelector<HTMLInputElement>('app-free-response-editor input[role="switch"]')!;
        expect(toggle.disabled).toBeTrue();
        expect(toggle.checked).toBeFalse();
        expect(root.querySelector('app-free-response-editor .badge')?.textContent?.trim()).toBe('ИИ');
        expect(root.textContent).toContain('Проверять смысл ответа с ИИ');
        expect(root.textContent).toContain('Проверка объяснений и формулировок по эталону. Пока недоступна.');
        expect(root.textContent).toContain('поставщик проверки не подключён');
        expect(root.textContent).toContain('Это не проверка смысла');
        toggle.click(); fixture.detectChanges();
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

    it('fails closed when capabilities cannot be read and for an exercise that needs an unavailable capability', () => {
        const ai = clone(mechanics['rejectedAiAssessment'].exercise) as ExerciseSpec;
        configure(detailOf(ai), 'error');
        expect(component().capabilities()).toEqual(CAPABILITIES_UNAVAILABLE);
        expect(page().querySelector<HTMLInputElement>('input[role="switch"]')?.disabled).toBeTrue();
        expect(page().querySelector<HTMLInputElement>('input[role="switch"]')?.checked).toBeTrue();
        component().save();
        expect(api.update).not.toHaveBeenCalled();
        expect(component().fieldErrors()['capability']).toContain('недоступна');
    });

    it('preserves what was typed for each mechanic when switching between them', () => {
        configure();
        component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Что такое память?')] });
        component().setType('CLOZE'); fixture.detectChanges();
        component().setCloze({ ...component().drafts().CLOZE, texts: ['Память — это '] });
        component().setType('CHOICE');
        component().setChoice({ ...component().drafts().CHOICE, prompt: [text('Выберите термин')] });
        component().setType('MATCH');
        component().setType('FREE_RESPONSE'); fixture.detectChanges();
        expect(page().querySelector<HTMLTextAreaElement>('#free-response-prompt-text-0')?.value).toBe('Что такое память?');
        component().setType('CLOZE'); fixture.detectChanges();
        expect(page().querySelector<HTMLTextAreaElement>('#cloze-text-0')?.value).toBe('Память — это ');
        component().setType('CHOICE'); fixture.detectChanges();
        expect(component().drafts().CHOICE.prompt).toEqual([text('Выберите термин')]);
        expect(component().dirty()).toBeTrue();
    });

    it('keeps the draft when microphone permission is denied inside a slot and never inserts a block', async () => {
        configure();
        component().setType('MATCH'); fixture.detectChanges();
        const root = page();
        const typed = [text('der Hund')];
        const pair = component().drafts().MATCH.pairs[0];
        component().setMatch({ ...component().drafts().MATCH, pairs: [{ ...pair, left: { ...pair.left, blocks: typed } },
            ...component().drafts().MATCH.pairs.slice(1)] });
        fixture.detectChanges();
        expect(getUserMedia).not.toHaveBeenCalled();
        const slots = root.querySelectorAll('app-match-editor [data-pair] app-exercise-slot-editor');
        buttonByText('+ Изображение, аудио или видео', slots[1]).click(); fixture.detectChanges();
        expect(slots[1].querySelector('app-native-media-upload')).not.toBeNull();
        expect(slots[0].querySelector('app-native-media-upload')).toBeNull();
        const before = JSON.stringify(component().drafts());
        buttonByText('Записать аудио', slots[1]).click();
        await fixture.whenStable(); fixture.detectChanges();
        expect(getUserMedia).toHaveBeenCalledTimes(1);
        expect(slots[1].textContent).toContain('Доступ к микрофону запрещён');
        expect(JSON.stringify(component().drafts())).toBe(before);
        expect(component().drafts().MATCH.pairs[0].left.blocks).toEqual(typed);
        expect(component().dirty()).toBeTrue();
    });

    it('inserts a chosen uploaded asset only into the slot whose picker produced it', () => {
        configure();
        component().setType('MATCH'); fixture.detectChanges();
        const slots = page().querySelectorAll('app-match-editor [data-pair] app-exercise-slot-editor');
        buttonByText('+ Изображение, аудио или видео', slots[2]).click(); fixture.detectChanges();
        buttonByText('+ Изображение, аудио или видео', slots[0]).click(); fixture.detectChanges();
        const upload = slots[2].querySelector('app-native-media-upload')!;
        const instance = fixture.debugElement.queryAll(el => el.nativeElement === upload)[0].componentInstance;
        instance.chooseAsset.emit({ kind: 'audio', assetId: id('99') });
        fixture.detectChanges();
        const pairs = component().drafts().MATCH.pairs;
        expect(pairs[1].left.blocks.map(block => block.kind)).toEqual(['TEXT', 'AUDIO']);
        expect(pairs[1].left.blocks[1]).toEqual({ kind: 'AUDIO', assetId: id('99'), title: 'Аудио' });
        expect(pairs[0].left.blocks.map(block => block.kind)).toEqual(['TEXT']);
        expect(pairs[0].right.blocks.map(block => block.kind)).toEqual(['TEXT']);
        expect(pairs[1].right.blocks.map(block => block.kind)).toEqual(['TEXT']);
        // A second media block in the same compact slot is refused with an explanation.
        instance.chooseAsset.emit({ kind: 'image', assetId: id('98') });
        fixture.detectChanges();
        expect(component().drafts().MATCH.pairs[1].left.blocks.length).toBe(2);
        expect(slots[2].textContent).toContain('уже есть изображение, аудио или видео');
    });

    it('requires an image description and an author label for media blocks', () => {
        configure();
        component().setType('SELF_CHECK');
        component().setSelfCheck({ prompt: [{ kind: 'IMAGE', assetId: id('99'), alt: '' }, text('?')],
            reference: [{ kind: 'AUDIO', assetId: id('98'), title: ' ' }] });
        component().save(); fixture.detectChanges();
        expect(api.create).not.toHaveBeenCalled();
        expect(component().fieldErrors()['prompt']).toContain('альтернативный текст обязателен');
        expect(component().fieldErrors()['reference']).toContain('Укажите название');
        expect(page().querySelector('[aria-invalid="true"]')).not.toBeNull();
    });

    it('renders the learner components as a local preview without any write or evaluation call', () => {
        configure();
        component().setType('MATCH');
        const spec = mechanics['createMatchMixed'].exercise;
        component().setMatch({ prompt: spec.content.prompt, pairs: spec.content.left.map((left: { itemId: string; blocks: AuthoringBlock[] }, index: number) => ({
            pairId: `pair-${index}`, left, right: spec.content.right[index] })) });
        fixture.detectChanges();
        const preview = page().querySelector('app-exercise-preview')!;
        expect(preview.querySelector('app-learner-exercise')).not.toBeNull();
        expect(preview.querySelectorAll('app-match-board li').length).toBe(8);
        expect(preview.querySelector('button[data-submit]')).toBeNull();
        // Local pair check against the draft: the first left item with a wrong, then its own partner.
        const left = () => [...preview.querySelectorAll<HTMLButtonElement>('button[data-side="left"]')];
        const right = () => [...preview.querySelectorAll<HTMLButtonElement>('button[data-side="right"]')];
        left()[0].click(); fixture.detectChanges();
        right()[0].click(); fixture.detectChanges();
        expect(preview.textContent).toContain('Эта пара не подходит');
        right()[3].click(); fixture.detectChanges();
        expect(preview.textContent).toContain('Пара 1 найдена');
        expect(api.create).not.toHaveBeenCalled();
        expect(api.update).not.toHaveBeenCalled();
        expect(getUserMedia).not.toHaveBeenCalled();
    });

    it('previews a cloze hint from the draft only in the author preview', () => {
        configure(detailOf(mechanics['createCloze'].exercise));
        fixture.detectChanges();
        const preview = page().querySelector('app-exercise-preview')!;
        expect(preview.querySelectorAll('app-cloze-passage input').length).toBe(3);
        preview.querySelector<HTMLButtonElement>('.cloze-hint')!.click(); fixture.detectChanges();
        expect(preview.querySelector('.cloze-letter')?.textContent).toContain('m');
    });

    describe('objective', () => {
        it('prefills the title from the question and lets the author override it', () => {
            configure();
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Что  такое\nинерция?')],
                answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'x' }] } });
            fixture.detectChanges();
            expect(page().querySelector<HTMLInputElement>('#objective-title')?.value).toBe('Что такое инерция?');
            component().setObjectiveTitle('Инерция');
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('другое')] });
            expect(component().effectiveTitle()).toBe('Инерция');
            component().save();
            expect(created().objective).toEqual({ operation: 'create', title: 'Инерция' });
        });

        it('requires a name and keeps the suggestion within 160 characters without splitting a surrogate pair', () => {
            configure();
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
            component().setFreeResponse({ ...component().drafts().FREE_RESPONSE, prompt: [text('Вопрос')],
                answer: { ...component().drafts().FREE_RESPONSE.answer, rows: [{ id: 'a', value: 'ответ' }] } });
        }

        it('reports a version conflict, keeps the input and reloads on request', () => {
            configure();
            fill();
            api.create.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'VERSION_CONFLICT' } })));
            component().save(); fixture.detectChanges();
            expect(component().phase()).toBe('conflict');
            expect(page().textContent).toContain('изменились в другой вкладке');
            expect(component().drafts().FREE_RESPONSE.prompt).toEqual([text('Вопрос')]);
            component().load();
            expect(api.list).toHaveBeenCalledTimes(2);
        });

        it('explains CAPABILITY_UNAVAILABLE separately from a conflict', () => {
            configure();
            fill();
            api.create.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { code: 'CAPABILITY_UNAVAILABLE' } })));
            component().save(); fixture.detectChanges();
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
            api.create.and.returnValue(of({ acknowledgement, replayed: true }));
            component().retry();
            expect(api.create.calls.mostRecent().args[5]).toBe(commandId);
        });

        it('turns a contract rejection raised before the request into a visible message', () => {
            configure();
            fill();
            api.create.and.returnValue(throwError(() => new AuthoringProtocolError('Invalid block.')));
            component().save(); fixture.detectChanges();
            expect(component().phase()).toBe('rejected');
            expect(page().textContent).toContain('не прошло проверку формата');
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

    it('lists the saved exercises of the material with mechanic names and objective titles', () => {
        configure(detailOf(mechanics['createChoiceVideoMultiple'].exercise));
        const row = page().querySelector('.existing li')!;
        expect(row.textContent).toContain('Выбрать ответ');
        expect(row.textContent).toContain('Признаки реакции в опыте');
    });

    it('has no horizontal overflow at 320, 390 and 1440 with long strings in every mechanic', () => {
        configure();
        const long = 'я'.repeat(300);
        component().setSelfCheck({ prompt: [text(long)], reference: [text(long)] });
        const root = page(); root.style.display = 'block';
        for (const type of ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH'] as const) {
            component().setType(type); fixture.detectChanges();
            for (const width of [320, 390, 1440]) {
                root.style.width = `${width}px`; fixture.detectChanges();
                expect(root.scrollWidth).withContext(`${type} at ${width}`).toBeLessThanOrEqual(width + 1);
            }
        }
    });
});

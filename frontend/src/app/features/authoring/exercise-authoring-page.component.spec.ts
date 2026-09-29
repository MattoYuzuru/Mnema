import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { NativeDocument } from '../../content/native-document';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ExerciseApiService } from './exercise-api.service';
import { ExerciseAuthoringPageComponent } from './exercise-authoring-page.component';
import { ExerciseDetail, ExercisePage } from './exercise.models';
import { ItemApiService } from './item-api.service';

describe('ExerciseAuthoringPageComponent', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deck: OwnDeck = {
        deckId: id('1'), revisionId: id('2'), rowVersion: '3', sequence: '3',
        metadata: { title: 'Память', description: '' }, visibility: 'private',
        createdAt: '2026-09-20T10:00:00Z', updatedAt: '2026-09-20T10:00:00Z', memberCount: 1, exerciseCount: 0
    };
    const nativeDocument: NativeDocument = { formatVersion: 1, root: {
        id: id('10'), type: 'doc', version: 1, attrs: {}, content: [
            { id: id('11'), type: 'paragraph', version: 1, attrs: {}, content: [
                { id: id('12'), type: 'text', version: 1, attrs: { text: 'What is memory?' }, content: [] }
            ] },
            { id: id('13'), type: 'paragraph', version: 1, attrs: {}, content: [
                { id: id('14'), type: 'text', version: 1, attrs: { text: 'Memory' }, content: [] }
            ] },
            { id: id('15'), type: 'paragraph', version: 1, attrs: {}, content: [
                { id: id('16'), type: 'text', version: 1, attrs: { text: 'Attention' }, content: [] }
            ] }
        ]
    } };
    const item = {
        memberKey: id('20'), itemRevisionId: id('21'), itemVersion: '0', ordinal: null, formatVersion: 1 as const,
        createdAt: '2026-09-20T10:00:00Z', updatedAt: '2026-09-20T10:00:00Z', deckId: deck.deckId,
        deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion, document: nativeDocument
    };
    const page: ExercisePage = { deckId: deck.deckId, deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion,
        total: 0, exercises: [], nextCursor: null };

    const detail: ExerciseDetail = {
        exerciseId: id('50'), exerciseRevisionId: id('51'), exerciseVersion: '1', ordinal: 0,
        type: 'CLOZE_SINGLE', enabled: true, schemaVersion: 1,
        createdAt: '2026-09-20T10:00:00Z', updatedAt: '2026-09-20T10:00:00Z',
        deckId: deck.deckId, deckRevisionId: deck.revisionId, deckVersion: deck.rowVersion,
        prompt: { kind: 'CUSTOM_TEXT', text: 'Complete this', blank: { mode: 'FIXED', length: 7 } },
        evaluatorPolicy: { id: 'deterministic-text', version: '1' },
        objective: { objectiveId: id('52'), objectiveKey: id('53'), objectiveRevisionId: id('54'), objectiveVersion: '1',
            memberKey: item.memberKey, answerContract: { schemaVersion: 1,
                normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'SOFT', accepted: ['Memory', 'Mémory'] } },
        bindings: [{ bindingId: id('55'), role: 'ASSESSED', memberKey: item.memberKey,
            itemRevisionId: item.itemRevisionId, nodeIds: [], display: { kind: 'CUSTOM_TEXT', text: 'Memory' }, ordinal: 0 }]
    };

    function configure(edit = false): { readonly api: jasmine.SpyObj<ExerciseApiService>; readonly router: jasmine.SpyObj<Router> } {
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        const items = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['read']);
        const api = jasmine.createSpyObj<ExerciseApiService>('ExerciseApiService', ['list', 'read', 'create', 'update', 'delete']);
        const router = jasmine.createSpyObj<Router>('Router', ['navigate']);
        decks.detail.and.returnValue(of(deck)); items.read.and.returnValue(of(item));
        api.list.and.returnValue(of(edit ? { ...page, total: 1, exercises: [detail] } : page));
        api.read.and.returnValue(of(detail));
        router.navigate.and.resolveTo(true);
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: {
                paramMap: convertToParamMap({ deckId: deck.deckId, ...(edit ? { exerciseId: detail.exerciseId } : { memberKey: item.memberKey }) }),
                queryParamMap: convertToParamMap({})
            } } },
            { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decks },
            { provide: ItemApiService, useValue: items }, { provide: ExerciseApiService, useValue: api }
        ] });
        return { api, router };
    }

    it('builds one explicit assessed direction and bounded choice options without technical input', () => {
        const { api, router } = configure();
        api.create.and.returnValue(of({ acknowledgement: { commandId: id('30'), deckId: deck.deckId,
            deckRevisionId: id('31'), deckVersion: '4', objectiveId: id('32'), objectiveKey: id('33'),
            objectiveRevisionId: id('34'), exerciseId: id('35'), exerciseRevisionId: id('36'), enabled: true },
            replayed: false }));
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.setType('SINGLE_CHOICE');
        component.setPromptNode(id('11'));
        component.setAnswerNode(id('13'));
        component.setAliases('Memory\nmemory');
        component.toggleOption(id('15'), true);
        component.save();

        const args = api.create.calls.mostRecent().args;
        expect(args[2]).toBe(deck.revisionId);
        expect(args[3]['operation']).toBe('create');
        const exercise = args[4];
        expect(exercise['type']).toBe('SINGLE_CHOICE');
        const bindings = exercise['bindings'] as Record<string, unknown>[];
        expect(bindings.filter(binding => binding['role'] === 'ASSESSED')).toHaveSize(1);
        expect(bindings.filter(binding => binding['role'] === 'OPTION')).toHaveSize(2);
        expect(component.dirty()).toBeFalse();
        expect(router.navigate).toHaveBeenCalled();
    });

    it('offers each text fragment once and saves a custom answer with synonyms', () => {
        const { api } = configure();
        api.create.and.returnValue(of({ acknowledgement: { commandId: id('30'), deckId: deck.deckId,
            deckRevisionId: id('31'), deckVersion: '4', objectiveId: id('32'), objectiveKey: id('33'),
            objectiveRevisionId: id('34'), exerciseId: id('35'), exerciseRevisionId: id('36'), enabled: true },
            replayed: false }));
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        expect(component.projections().map(projection => projection.text))
            .toEqual(['What is memory?', 'Memory', 'Attention']);
        component.setPromptNode(id('11'));
        component.setAnswerMode('custom');
        component.setAliases('Long-term memory\nDurable memory');
        component.save();
        const args = api.create.calls.mostRecent().args;
        expect(args[3]['answerContract']).toEqual({ schemaVersion: 1,
            normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'],
            matchingMode: 'STRICT',
            accepted: ['Long-term memory', 'Durable memory'] });
        expect((args[4]['bindings'] as Record<string, unknown>[])[0]).toEqual(jasmine.objectContaining({
            nodeIds: [], display: { kind: 'CUSTOM_TEXT', text: 'Long-term memory' }
        }));
    });

    it('preserves input and exact command across an unknown-outcome retry', () => {
        const { api } = configure();
        api.create.and.returnValues(throwError(() => new HttpErrorResponse({ status: 0 })), of({
            acknowledgement: { commandId: id('30'), deckId: deck.deckId, deckRevisionId: id('31'), deckVersion: '4',
                objectiveId: id('32'), objectiveKey: id('33'), objectiveRevisionId: id('34'), exerciseId: id('35'),
                exerciseRevisionId: id('36'), enabled: true }, replayed: true
        }));
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.setPromptNode(id('11')); component.setAnswerNode(id('13')); component.setAliases('Memory');
        component.save();
        const first = api.create.calls.argsFor(0);
        expect(component.phase()).toBe('error');
        component.retry();
        expect(api.create.calls.argsFor(1)).toEqual(first);
    });

    it('blocks stale or incomplete projections while retaining the draft', () => {
        configure();
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.setPromptNode(id('99')); component.setAnswerNode(id('13')); component.setAliases('Memory');
        component.save();
        expect(component.phase()).toBe('rejected');
        expect(component.fieldErrors()['prompt']).toContain('актуальный');
        expect(component.dirty()).toBeTrue();
    });

    it('authors an independent audio-text match map with pinned cue IDs', () => {
        const { api } = configure();
        api.create.and.returnValue(of({ acknowledgement: { commandId: id('30'), deckId: deck.deckId,
            deckRevisionId: id('31'), deckVersion: '4', objectiveId: id('32'), objectiveKey: id('33'),
            objectiveRevisionId: id('34'), exerciseId: id('35'), exerciseRevisionId: id('36'), enabled: true },
            replayed: false }));
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.setType('AUDIO_TEXT_MATCH'); component.setAudio('instruction', 'Соотнесите записи');
        component.setAnswerNode(id('13'));
        component.setMatchRow(0, 'assetId', id('40')); component.setMatchRow(0, 'title', 'Memory');
        component.setMatchRow(0, 'optionNodeId', id('13'));
        component.setMatchRow(1, 'assetId', id('41')); component.setMatchRow(1, 'title', 'Attention');
        component.setMatchRow(1, 'optionNodeId', id('15'));
        component.save();
        const args = api.create.calls.mostRecent().args;
        const prompt = (args[4]['prompt'] as Record<string, unknown>);
        const cues = prompt['cues'] as Record<string, unknown>[];
        const bindings = args[4]['bindings'] as Record<string, unknown>[];
        const answer = args[3]['answerContract'] as Record<string, unknown>;
        expect(prompt['kind']).toBe('AUDIO_MATCH');
        expect(cues).toHaveSize(2);
        expect(answer['schemaVersion']).toBe(2);
        expect(answer['pairs']).toEqual([
            { cueId: cues[0]['cueId'], optionId: bindings[1]['bindingId'] },
            { cueId: cues[1]['cueId'], optionId: bindings[2]['bindingId'] }
        ]);
    });

    it('routes the shared upload queue to the selected audio cue', () => {
        configure();
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.setType('AUDIO_TEXT_MATCH');
        component.selectAudioTarget(1);
        component.chooseUploadedAudio({ kind: 'audio', assetId: id('42') });
        expect(component.matchRows()[1].assetId).toBe(id('42'));
        expect(component.matchRows()[0].assetId).toBe('');
        component.chooseUploadedAudio({ kind: 'image', assetId: id('43') });
        expect(component.matchRows()[1].assetId).toBe(id('42'));
    });

    it('limits answer rows and serializes cloze length and soft matching', () => {
        const { api } = configure();
        api.create.and.returnValue(of({ acknowledgement: { commandId: id('30'), deckId: deck.deckId,
            deckRevisionId: id('31'), deckVersion: '4', objectiveId: id('32'), objectiveKey: id('33'),
            objectiveRevisionId: id('34'), exerciseId: id('35'), exerciseRevisionId: id('36'), enabled: true },
            replayed: false }));
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.setType('CLOZE_SINGLE'); component.setPromptNode(id('11')); component.setAnswerNode(id('13'));
        component.addAlias(); component.setAlias(1, 'Mémory'); component.setMatchingMode('SOFT');
        component.setBlankMode('FIXED'); component.setFixedBlankLength(9);
        component.save();
        expect(api.create).toHaveBeenCalled();
        const args = api.create.calls.mostRecent().args;
        expect(args[4]['prompt']).toEqual(jasmine.objectContaining({ blank: { mode: 'FIXED', length: 9 } }));
        expect((args[3]['answerContract'] as Record<string, unknown>)['matchingMode']).toBe('SOFT');
        for (let index = 0; index < 30; index++) component.addAlias();
        expect(component.aliasRows()).toHaveSize(20);
    });

    it('blocks empty and repeated answer rows before a write', () => {
        const { api } = configure();
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.setPromptNode(id('11')); component.setAnswerNode(id('13'));
        component.addAlias(); component.save();
        expect(component.fieldErrors()['aliases']).toContain('пустые');
        component.setAlias(1, 'Memory'); component.save();
        expect(component.fieldErrors()['aliases']).toContain('повторяющиеся');
        expect(api.create).not.toHaveBeenCalled();
    });

    it('rejects answer-length disclosure when accepted lengths differ and restores a saved edit', () => {
        const { api } = configure(true);
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        expect(component.fixedBlankLength()).toBe(7);
        expect(component.matchingMode()).toBe('SOFT');
        component.setMatchingMode('STRICT');
        expect(component.objectiveMode()).toBe('revise');
        component.setBlankMode('ANSWER_LENGTH'); component.setAlias(1, 'LongerX'); component.save();
        expect(component.fieldErrors()['blank']).toContain('одинаковой длины');
        expect(api.update).not.toHaveBeenCalled();
        component.resetChanges();
        expect(component.blankMode()).toBe('FIXED');
        expect(component.matchingMode()).toBe('SOFT');
        expect(component.aliasRows()[1].value).toBe('Mémory');
        expect(component.dirty()).toBeFalse();
    });

    it('deletes the current exercise with the deck precondition and returns to material', () => {
        const { api, router } = configure(true);
        api.delete.and.returnValue(of(undefined));
        const component = TestBed.runInInjectionContext(() => new ExerciseAuthoringPageComponent());
        component.deleteExercise();
        expect(api.delete).toHaveBeenCalledOnceWith(deck.deckId, detail.exerciseId, deck.rowVersion);
        expect(router.navigate).toHaveBeenCalledWith(['/decks', deck.deckId, 'materials', item.memberKey]);
    });

    it('keeps audio authoring and recording controls usable on a narrow viewport', () => {
        configure();
        const fixture = TestBed.createComponent(ExerciseAuthoringPageComponent);
        const host = fixture.nativeElement as HTMLElement;
        host.style.display = 'block'; host.style.width = '320px';
        fixture.componentInstance.setType('LISTEN_TYPE'); fixture.detectChanges();
        expect(host.textContent).toContain('Выберите аудиофайл ниже');
        expect(host.querySelector('input#audio-asset')).toBeNull();
        expect(host.querySelector('app-native-media-upload')).not.toBeNull();
        expect(host.scrollWidth).toBeLessThanOrEqual(321);
    });

    it('renders labelled controls without leaking IDs and reflows at accepted viewport and text sizes', async () => {
        configure();
        const fixture = TestBed.createComponent(ExerciseAuthoringPageComponent);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        host.style.display = 'block';
        host.setAttribute('dir', 'rtl');
        fixture.componentInstance.setPromptMode('custom');
        fixture.componentInstance.setCustomPrompt('ما معنى الذاكرة؟ 記憶とは何ですか');
        fixture.detectChanges();

        expect(host.querySelectorAll('fieldset legend').length).toBeGreaterThanOrEqual(4);
        expect(host.querySelector('label[for="custom-prompt"]')).not.toBeNull();
        expect(host.textContent).not.toContain(item.memberKey);
        expect(fixture.componentInstance.customPrompt()).toContain('記憶');

        for (const width of [320, 390, 1440]) {
            host.style.width = `${width}px`;
            document.documentElement.style.fontSize = width === 320 ? '32px' : '16px';
            fixture.detectChanges();
            expect(host.scrollWidth).toBeLessThanOrEqual(width + 1);
        }
        document.documentElement.style.fontSize = '';
        host.removeAttribute('dir');

        (host.querySelector('button[type="submit"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        await fixture.whenStable();
        expect(host.querySelector('[role="alert"]')).not.toBeNull();
        expect(host.querySelectorAll('[aria-invalid="true"]').length).toBeGreaterThan(0);
    });
});

import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { NativeDocument } from '../../content/native-document';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { ExerciseApiService } from './exercise-api.service';
import { ExerciseAuthoringPageComponent } from './exercise-authoring-page.component';
import { ExercisePage } from './exercise.models';
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

    function configure(): { readonly api: jasmine.SpyObj<ExerciseApiService>; readonly router: jasmine.SpyObj<Router> } {
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        const items = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['read']);
        const api = jasmine.createSpyObj<ExerciseApiService>('ExerciseApiService', ['list', 'read', 'create', 'update']);
        const router = jasmine.createSpyObj<Router>('Router', ['navigate']);
        decks.detail.and.returnValue(of(deck)); items.read.and.returnValue(of(item)); api.list.and.returnValue(of(page));
        router.navigate.and.resolveTo(true);
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: {
                paramMap: convertToParamMap({ deckId: deck.deckId, memberKey: item.memberKey }),
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

import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

import { createEmptyNativeDocument } from '../../content/editing/native-editor-adapter';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringApiService } from './authoring-api.service';
import { DraftDetail } from './authoring.models';
import { ItemEditorPageComponent } from './item-editor-page.component';
import { ItemApiService } from './item-api.service';

describe('ItemEditorPageComponent recovery', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deck: OwnDeck = {
        deckId: id('1'), revisionId: id('2'), rowVersion: '0', sequence: '0',
        metadata: { title: 'Колода', description: '' }, visibility: 'private',
        createdAt: '2026-09-19T10:00:00Z', updatedAt: '2026-09-19T10:00:00Z',
        memberCount: 0, exerciseCount: 0
    };

    function configure(memberKey: string | null = null, ordinal: string | null = null): {
        readonly decks: jasmine.SpyObj<OwnDecksApiService>;
        readonly items: jasmine.SpyObj<ItemApiService>;
        readonly authoring: jasmine.SpyObj<AuthoringApiService>;
        readonly router: jasmine.SpyObj<Router>;
    } {
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        const items = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['read', 'create', 'save']);
        const authoring = jasmine.createSpyObj<AuthoringApiService>('AuthoringApiService', [
            'listAllDrafts', 'createDraft', 'readDraft', 'updateDraft', 'deleteDraft'
        ]);
        const router = jasmine.createSpyObj<Router>('Router', ['navigate']);
        decks.detail.and.returnValue(of(deck));
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: {
                snapshot: {
                    paramMap: convertToParamMap(memberKey === null
                        ? { deckId: deck.deckId } : { deckId: deck.deckId, memberKey }),
                    queryParamMap: convertToParamMap(ordinal === null ? {} : { ordinal })
                }
            } },
            { provide: Router, useValue: router },
            { provide: OwnDecksApiService, useValue: decks },
            { provide: ItemApiService, useValue: items },
            { provide: AuthoringApiService, useValue: authoring }
        ] });
        return { decks, items, authoring, router };
    }

    it('retries unknown-outcome draft creation exactly and reconciles a replay with GET', () => {
        const { authoring } = configure();
        authoring.listAllDrafts.and.returnValue(of({ items: [], nextCursor: null }));
        let attempt = 0;
        authoring.createDraft.and.callFake((_deckId, document, _memberKey, _baseRevisionId, commandId) => {
            if (attempt++ === 0) return throwError(() => new HttpErrorResponse({ status: 0 }));
            const draft: DraftDetail = {
                draftId: id('3'), deckId: deck.deckId, memberKey: null, baseRevisionId: null, rowVersion: '0',
                contentBytes: 100, createdAt: '2026-09-19T10:00:00Z', acknowledgedAt: '2026-09-19T10:00:00Z',
                expiresAt: '2026-10-19T10:00:00Z', document
            };
            authoring.readDraft.and.returnValue(of(draft));
            return of({ acknowledgement: { commandId, draft }, replayed: true });
        });
        const component = TestBed.runInInjectionContext(() => new ItemEditorPageComponent());
        const original = authoring.createDraft.calls.argsFor(0);
        expect(component.phase()).toBe('error');
        component.retry();

        expect(authoring.createDraft.calls.argsFor(1)).toEqual(original);
        expect(authoring.readDraft).toHaveBeenCalledOnceWith(id('3'));
        expect(component.phase()).toBe('ready');
        expect(component.draft()?.draftId).toBe(id('3'));
    });

    it('keeps a deterministic draft rejection editable and allocates a new command after correction', () => {
        const { authoring } = configure();
        const document = createEmptyNativeDocument();
        const draft: DraftDetail = {
            draftId: id('3'), deckId: deck.deckId, memberKey: null, baseRevisionId: null, rowVersion: '0',
            contentBytes: 100, createdAt: '2026-09-19T10:00:00Z', acknowledgedAt: '2026-09-19T10:00:00Z',
            expiresAt: '2026-10-19T10:00:00Z', document
        };
        authoring.listAllDrafts.and.returnValue(of({ items: [draft], nextCursor: null }));
        authoring.readDraft.and.returnValue(of(draft));
        authoring.updateDraft.and.returnValues(
            throwError(() => new HttpErrorResponse({ status: 422 })),
            of({ acknowledgement: { commandId: id('5'), draft: { ...draft, rowVersion: '1' } }, replayed: false })
        );
        const component = TestBed.runInInjectionContext(() => new ItemEditorPageComponent());

        component.changeDocument(document);
        component.saveDraft();
        const rejectedCommand = authoring.updateDraft.calls.argsFor(0)[3];
        expect(component.phase()).toBe('rejected');

        component.changeDocument(document);
        expect(component.phase()).toBe('ready');
        component.saveDraft();
        expect(authoring.updateDraft.calls.argsFor(1)[3]).not.toBe(rejectedCommand);
        expect(component.phase()).toBe('ready');
    });

    it('marks a replayed draft mismatch as an explicit draft conflict', () => {
        const { authoring } = configure();
        const document = createEmptyNativeDocument();
        const serverDocument = { ...document, root: { ...document.root, attrs: { lang: 'ru' } } };
        const draft: DraftDetail = {
            draftId: id('3'), deckId: deck.deckId, memberKey: null, baseRevisionId: null, rowVersion: '0',
            contentBytes: 100, createdAt: '2026-09-19T10:00:00Z', acknowledgedAt: '2026-09-19T10:00:00Z',
            expiresAt: '2026-10-19T10:00:00Z', document
        };
        authoring.listAllDrafts.and.returnValue(of({ items: [draft], nextCursor: null }));
        authoring.readDraft.and.returnValues(of(draft), of({ ...draft, rowVersion: '1', document: serverDocument }));
        authoring.updateDraft.and.returnValue(of({
            acknowledgement: { commandId: id('5'), draft: { ...draft, rowVersion: '1' } }, replayed: true
        }));
        const component = TestBed.runInInjectionContext(() => new ItemEditorPageComponent());

        component.changeDocument(document);
        component.saveDraft();

        expect(component.phase()).toBe('conflict');
        expect(component.conflict()).toBe('draft');
        expect(component.document()).toBe(document);
    });
});

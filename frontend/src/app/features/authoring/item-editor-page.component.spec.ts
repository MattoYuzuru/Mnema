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
import { spyObj, type SpyObj } from '../../../testing/mocks';

describe('ItemEditorPageComponent recovery', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deck: OwnDeck = {
        deckId: id('1'), revisionId: id('2'), rowVersion: '0', sequence: '0',
        metadata: { title: 'Колода', description: '' }, visibility: 'private',
        createdAt: '2026-09-19T10:00:00Z', updatedAt: '2026-09-19T10:00:00Z',
        memberCount: 0, exerciseCount: 0
    };

    function configure(memberKey: string | null = null, ordinal: string | null = null): {
        readonly decks: SpyObj<OwnDecksApiService>;
        readonly items: SpyObj<ItemApiService>;
        readonly authoring: SpyObj<AuthoringApiService>;
        readonly router: SpyObj<Router>;
    } {
        const decks = spyObj<OwnDecksApiService>({
            detail: vi.fn().mockName("OwnDecksApiService.detail")
        });
        const items = spyObj<ItemApiService>({
            read: vi.fn().mockName("ItemApiService.read"),
            create: vi.fn().mockName("ItemApiService.create"),
            save: vi.fn().mockName("ItemApiService.save")
        });
        const authoring = spyObj<AuthoringApiService>({
            listAllDrafts: vi.fn().mockName("AuthoringApiService.listAllDrafts"),
            createDraft: vi.fn().mockName("AuthoringApiService.createDraft"),
            readDraft: vi.fn().mockName("AuthoringApiService.readDraft"),
            updateDraft: vi.fn().mockName("AuthoringApiService.updateDraft"),
            deleteDraft: vi.fn().mockName("AuthoringApiService.deleteDraft")
        });
        const router = spyObj<Router>({
            navigate: vi.fn().mockName("Router.navigate")
        });
        decks.detail.mockReturnValue(of(deck));
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
        authoring.listAllDrafts.mockReturnValue(of({ items: [], nextCursor: null }));
        let attempt = 0;
        authoring.createDraft.mockImplementation((_deckId, document, _memberKey, _baseRevisionId, commandId) => {
            if (attempt++ === 0)
                return throwError(() => new HttpErrorResponse({ status: 0 }));
            const draft: DraftDetail = {
                draftId: id('3'), deckId: deck.deckId, memberKey: null, baseRevisionId: null, rowVersion: '0',
                contentBytes: 100, createdAt: '2026-09-19T10:00:00Z', acknowledgedAt: '2026-09-19T10:00:00Z',
                expiresAt: '2026-10-19T10:00:00Z', document
            };
            authoring.readDraft.mockReturnValue(of(draft));
            return of({ acknowledgement: { commandId, draft }, replayed: true });
        });
        const component = TestBed.runInInjectionContext(() => new ItemEditorPageComponent());
        const original = vi.mocked(authoring.createDraft).mock.calls[0];
        expect(component.phase()).toBe('error');
        component.retry();

        expect(vi.mocked(authoring.createDraft).mock.calls[1]).toEqual(original);
        expect(authoring.readDraft).toHaveBeenCalledTimes(1);
        expect(authoring.readDraft).toHaveBeenCalledWith(id('3'));
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
        authoring.listAllDrafts.mockReturnValue(of({ items: [draft], nextCursor: null }));
        authoring.readDraft.mockReturnValue(of(draft));
        authoring.updateDraft.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 422 }))).mockReturnValueOnce(of({ acknowledgement: { commandId: id('5'), draft: { ...draft, rowVersion: '1' } }, replayed: false }));
        const component = TestBed.runInInjectionContext(() => new ItemEditorPageComponent());

        component.changeDocument(document);
        component.saveDraft();
        const rejectedCommand = vi.mocked(authoring.updateDraft).mock.calls[0][3];
        expect(component.phase()).toBe('rejected');

        component.changeDocument(document);
        expect(component.phase()).toBe('ready');
        component.saveDraft();
        expect(vi.mocked(authoring.updateDraft).mock.calls[1][3]).not.toBe(rejectedCommand);
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
        authoring.listAllDrafts.mockReturnValue(of({ items: [draft], nextCursor: null }));
        authoring.readDraft.mockReturnValueOnce(of(draft)).mockReturnValueOnce(of({ ...draft, rowVersion: '1', document: serverDocument }));
        authoring.updateDraft.mockReturnValue(of({
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

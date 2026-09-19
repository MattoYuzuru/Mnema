import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';

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

    it('retries unknown-outcome draft creation exactly and reconciles a replay with GET', () => {
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        decks.detail.and.returnValue(of(deck));
        const items = jasmine.createSpyObj<ItemApiService>('ItemApiService', ['read']);
        const authoring = jasmine.createSpyObj<AuthoringApiService>('AuthoringApiService', [
            'listAllDrafts', 'createDraft', 'readDraft', 'updateDraft', 'deleteDraft'
        ]);
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
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: {
                snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }), queryParamMap: convertToParamMap({}) }
            } },
            { provide: Router, useValue: jasmine.createSpyObj<Router>('Router', ['navigate']) },
            { provide: OwnDecksApiService, useValue: decks },
            { provide: ItemApiService, useValue: items },
            { provide: AuthoringApiService, useValue: authoring }
        ] });

        const component = TestBed.runInInjectionContext(() => new ItemEditorPageComponent());
        const original = authoring.createDraft.calls.argsFor(0);
        expect(component.phase()).toBe('error');
        component.retry();

        expect(authoring.createDraft.calls.argsFor(1)).toEqual(original);
        expect(authoring.readDraft).toHaveBeenCalledOnceWith(id('3'));
        expect(component.phase()).toBe('ready');
        expect(component.draft()?.draftId).toBe(id('3'));
    });
});

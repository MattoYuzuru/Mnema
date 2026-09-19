import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringApiService } from './authoring-api.service';
import { CaptureNote, CaptureWriteResult } from './authoring.models';
import { CapturePageComponent, documentFromText } from './capture-page.component';

describe('documentFromText', () => {
    it('preserves long multilingual text as one native-v1 text node with stable unique IDs', () => {
        const text = `${'Длинный русский текст. '.repeat(300)}العِلْمُ نورٌ 漢字`;
        const document = documentFromText(text);
        const paragraph = document.root.content[0]!;
        const capturedText = paragraph.content[0]?.attrs['text'] as string;
        expect(capturedText).toBe(text);
        expect(new Set([document.root.id, paragraph.id, paragraph.content[0]!.id]).size).toBe(3);
        expect(document.formatVersion).toBe(1);
    });
});

describe('CapturePageComponent', () => {
    let fixture: ComponentFixture<CapturePageComponent>;
    let api: jasmine.SpyObj<AuthoringApiService>;
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const deck: OwnDeck = {
        deckId: id('1'), revisionId: id('2'), rowVersion: '0', sequence: '0',
        metadata: { title: 'Колода', description: '' }, visibility: 'private',
        createdAt: '2026-09-19T10:00:00Z', updatedAt: '2026-09-19T10:00:00Z',
        memberCount: 0, exerciseCount: 0
    };
    const capture: CaptureNote = {
        noteId: id('3'), deckId: deck.deckId, rowVersion: '0', source: 'manual', text: 'Мысль', contentBytes: 12,
        archived: false, createdAt: '2026-09-19T10:00:00Z', updatedAt: '2026-09-19T10:00:00Z', conversion: null
    };

    beforeEach(async () => {
        api = jasmine.createSpyObj<AuthoringApiService>('AuthoringApiService', [
            'listCaptures', 'createCapture', 'convertCapture'
        ]);
        api.listCaptures.and.returnValue(of({ items: [], nextCursor: null }));
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        decks.detail.and.returnValue(of(deck));
        await TestBed.configureTestingModule({
            imports: [CapturePageComponent],
            providers: [
                provideRouter([]),
                { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
                { provide: OwnDecksApiService, useValue: decks },
                { provide: AuthoringApiService, useValue: api }
            ]
        }).compileComponents();
        fixture = TestBed.createComponent(CapturePageComponent);
        fixture.detectChanges();
    });

    it('retries an unknown-outcome capture with the exact command and payload', () => {
        const accepted: CaptureWriteResult = {
            acknowledgement: { commandId: id('4'), capture }, replayed: true
        };
        api.createCapture.and.returnValues(
            throwError(() => new HttpErrorResponse({ status: 0 })),
            of(accepted)
        );
        fixture.componentInstance.form.setValue({ text: 'Мысль' });

        fixture.componentInstance.capture();
        const original = api.createCapture.calls.argsFor(0);
        expect(fixture.componentInstance.recovery()).toBe('retry');
        fixture.componentInstance.retry();

        expect(api.createCapture.calls.argsFor(1)).toEqual(original);
        expect(fixture.componentInstance.notes()).toEqual([capture]);
        expect(fixture.componentInstance.recovery()).toBeNull();
    });
});

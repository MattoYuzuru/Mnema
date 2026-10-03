import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringApiService } from './authoring-api.service';
import { CaptureNote, CaptureWriteResult } from './authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from './capabilities-api.service';
import { CapturePageComponent, documentFromText } from './capture-page.component';
import { spyObj, type SpyObj } from '../../../testing/mocks';

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
    let api: SpyObj<AuthoringApiService>;
    let capabilities: SpyObj<CapabilitiesApiService>;
    const aiOn: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null } };
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
    const originalObserver = window.IntersectionObserver;

    afterEach(() => { window.IntersectionObserver = originalObserver; });

    beforeEach(async () => {
        api = spyObj<AuthoringApiService>({
            listDeckCaptures: vi.fn().mockName("AuthoringApiService.listDeckCaptures"),
            createCapture: vi.fn().mockName("AuthoringApiService.createCapture"),
            convertCapture: vi.fn().mockName("AuthoringApiService.convertCapture"),
            deleteCapture: vi.fn().mockName("AuthoringApiService.deleteCapture")
        });
        api.listDeckCaptures.mockReturnValue(of({ items: [], nextCursor: null, total: 0 }));
        const decks = {
            detail: vi.fn().mockName("OwnDecksApiService.detail")
        };
        decks.detail.mockReturnValue(of(deck));
        capabilities = spyObj<CapabilitiesApiService>({ read: vi.fn().mockName('CapabilitiesApiService.read') });
        capabilities.read.mockReturnValue(of(aiOn));
        await TestBed.configureTestingModule({
            imports: [CapturePageComponent],
            providers: [
                provideRouter([]),
                { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
                { provide: OwnDecksApiService, useValue: decks },
                { provide: AuthoringApiService, useValue: api },
                { provide: CapabilitiesApiService, useValue: capabilities }
            ]
        }).compileComponents();
        fixture = TestBed.createComponent(CapturePageComponent);
        fixture.detectChanges();
    });

    it('retries an unknown-outcome capture with the exact command and payload', () => {
        const accepted: CaptureWriteResult = {
            acknowledgement: { commandId: id('4'), capture }, replayed: true
        };
        api.createCapture.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 }))).mockReturnValueOnce(of(accepted));
        fixture.componentInstance.form.setValue({ text: 'Мысль' });

        fixture.componentInstance.capture();
        const original = vi.mocked(api.createCapture).mock.calls[0];
        expect(fixture.componentInstance.recovery()).toBe('retry');
        fixture.componentInstance.retry();

        expect(vi.mocked(api.createCapture).mock.calls[1]).toEqual(original);
        expect(fixture.componentInstance.notes()).toEqual([capture]);
        expect(fixture.componentInstance.recovery()).toBeNull();
    });

    it('removes a note after a confirmed response and decrements the remaining count', () => {
        fixture.componentInstance.notes.set([capture]);
        fixture.componentInstance.total.set(1);
        api.deleteCapture.mockReturnValue(of(void 0));
        fixture.componentInstance.deleteNote(capture);
        fixture.detectChanges();

        expect(api.deleteCapture).toHaveBeenCalledTimes(1);

        expect(api.deleteCapture).toHaveBeenCalledWith(capture);
        expect(fixture.componentInstance.notes()).toEqual([]);
        expect(fixture.componentInstance.total()).toBe(0);
    });

    it('keeps a note visible if deletion is not confirmed', () => {
        fixture.componentInstance.notes.set([capture]);
        fixture.componentInstance.total.set(1);
        api.deleteCapture.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 412 })));
        fixture.componentInstance.deleteNote(capture);

        expect(fixture.componentInstance.notes()).toEqual([capture]);
        expect(fixture.componentInstance.total()).toBe(1);
        expect(fixture.componentInstance.recovery()).toBe('reload');
    });

    it('prefetches the next deck-scoped note page before the end of the list', () => {
        let onIntersection: IntersectionObserverCallback = () => undefined;
        window.IntersectionObserver = class {
            constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
                onIntersection = callback;
                expect(options?.rootMargin).toBe('0px 0px 800px 0px');
            }
            observe(): void { }
            disconnect(): void { }
        } as unknown as typeof IntersectionObserver;
        const nextNote = { ...capture, noteId: id('8'), text: 'Вторая мысль' };
        api.listDeckCaptures.mockReturnValueOnce(of({ items: [capture], nextCursor: 'page-two', total: 2 })).mockReturnValueOnce(of({ items: [nextNote], nextCursor: null, total: 2 }));

        fixture.componentInstance.load();
        fixture.detectChanges();
        onIntersection([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
        fixture.detectChanges();

        expect(vi.mocked(api.listDeckCaptures).mock.lastCall).toEqual([deck.deckId, 'page-two']);
        expect(fixture.componentInstance.notes().map(note => note.noteId)).toEqual([capture.noteId, nextNote.noteId]);
        expect(fixture.nativeElement.textContent).not.toContain('Показать ещё');
    });

    describe('picking notes for «Создать материалы с ИИ» (#290)', () => {
        const noteWith = (suffix: string, text = `Заметка ${suffix}`): CaptureNote => ({ ...capture, noteId: id(suffix), text });
        const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
        const boxes = (): HTMLInputElement[] => [...root().querySelectorAll<HTMLInputElement>('.note-check input')];
        const headerBox = (): HTMLInputElement => root().querySelector<HTMLInputElement>('.select-all input')!;
        const counter = (): string => root().querySelector('.selection-count')!.textContent!.trim();
        const bar = (): HTMLElement | null => root().querySelector('.selection-bar');
        const create = (): HTMLButtonElement | undefined => [...root().querySelectorAll<HTMLButtonElement>('.selection-bar button')]
            .find(button => button.textContent!.trim() === 'Создать материалы с ИИ');

        async function load(count: number, available: LearningCapabilities = aiOn): Promise<CaptureNote[]> {
            const notes = Array.from({ length: count }, (_, index) => noteWith(String(100 + index)));
            capabilities.read.mockReturnValue(of(available));
            api.listDeckCaptures.mockReturnValue(of({ items: notes, nextCursor: null, total: count }));
            fixture = TestBed.createComponent(CapturePageComponent);
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            return notes;
        }

        function pick(box: HTMLInputElement): void {
            box.click();
            fixture.detectChanges();
        }

        it('gives every note a real checkbox with its own name, a counter and no bar until something is picked', async () => {
            await load(3);
            expect(boxes()).toHaveLength(3);
            expect(boxes().map(box => box.getAttribute('aria-label'))).toEqual(['Выбрать заметку: Заметка 100', 'Выбрать заметку: Заметка 101', 'Выбрать заметку: Заметка 102']);
            expect(boxes().every(box => box.type === 'checkbox')).toBe(true);
            expect(counter()).toBe('Выбрано: 0');
            expect(bar()).toBeNull();
            pick(boxes()[1]!);
            expect(counter()).toBe('Выбрано: 1');
            expect(bar()?.getAttribute('aria-label')).toBe('Действия с выбранными заметками');
            expect(bar()?.textContent).toContain('Выбрано: 1');
            expect(root().querySelector('.capture-card.is-selected')?.textContent).toContain('Заметка 101');
        });

        it('keeps the counter out of every live region: only the refusal region speaks', async () => {
            await load(2);
            pick(boxes()[0]!);
            expect(root().querySelector('.selection-count')?.closest('[aria-live], [role=status], [role=alert]')).toBeNull();
            expect(bar()?.closest('[aria-live], [role=status], [role=alert]')).toBeNull();
            expect(root().querySelectorAll('.selection-limit[role=status]')).toHaveLength(1);
            expect(root().querySelector('.selection-limit')?.textContent).toBe('');
        });

        it('«Выбрать все загруженные» picks every loaded note, shows the mixed state in between and clears on the next press', async () => {
            await load(3);
            expect(headerBox().checked).toBe(false);
            expect(root().querySelector('.select-all')?.textContent).toContain('Выбрать все загруженные');
            pick(boxes()[0]!);
            expect(headerBox().indeterminate).toBe(true);
            pick(headerBox());
            expect(counter()).toBe('Выбрано: 3');
            expect(headerBox().checked).toBe(true);
            expect(headerBox().indeterminate).toBe(false);
            pick(headerBox());
            expect(counter()).toBe('Выбрано: 0');
            expect(bar()).toBeNull();
        });

        it('explains the limit of 20 instead of dropping the 21st silently, and keeps its box unchecked', async () => {
            await load(21);
            boxes().slice(0, 20).forEach(box => pick(box));
            expect(counter()).toBe('Выбрано: 20');
            expect(root().querySelector('.selection-limit')?.textContent).toContain('Выбрано максимум: за один раз можно не больше 20 заметок.');
            pick(boxes()[20]!);
            expect(boxes()[20]!.checked).toBe(false);
            expect(counter()).toBe('Выбрано: 20');
            expect(root().querySelector('.selection-limit')?.textContent).toContain('Создайте материалы по этим, а потом вернитесь за остальными.');
            pick(boxes()[0]!);
            expect(counter()).toBe('Выбрано: 19');
            expect(root().querySelector('.selection-limit')?.textContent).toBe('');
        });

        it('says when «Выбрать все загруженные» took only the first 20', async () => {
            await load(25);
            pick(headerBox());
            expect(counter()).toBe('Выбрано: 20');
            expect(root().querySelector('.selection-limit')?.textContent).toContain('Выбрали первые 20 из 25 загруженных');
            expect(boxes().filter(box => box.checked)).toHaveLength(20);
            expect(headerBox().checked).toBe(true);
            expect(headerBox().indeterminate).toBe(false);
            pick(headerBox());
            expect(counter()).toBe('Выбрано: 0');
            pick(headerBox());
            fixture.componentInstance.clearSelection();
            fixture.detectChanges();
            expect(counter()).toBe('Выбрано: 0');
            expect(root().querySelector('.selection-limit')?.textContent).toBe('');
        });

        it('opens the composer with the picked notes in list order, in the query of the address', async () => {
            const notes = await load(4);
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            pick(boxes()[3]!);
            pick(boxes()[1]!);
            create()!.click();
            expect(navigate).toHaveBeenCalledWith(['/decks', deck.deckId, 'materials', 'new'],
                { queryParams: { notes: `${notes[1]!.noteId},${notes[3]!.noteId}` } });
        });

        it('has no button, no checkboxes and no counter without the aiGeneration capability, and when it cannot be read', async () => {
            await load(2, CAPABILITIES_UNAVAILABLE);
            expect(boxes()).toHaveLength(0);
            expect(root().querySelector('.select-all')).toBeNull();
            expect(bar()).toBeNull();
            expect(root().textContent).not.toContain('Создать материалы с ИИ');
            capabilities.read.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
            TestBed.resetTestingModule();
            await TestBed.configureTestingModule({ imports: [CapturePageComponent], providers: [provideRouter([]),
                { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
                { provide: OwnDecksApiService, useValue: { detail: () => of(deck) } }, { provide: AuthoringApiService, useValue: api },
                { provide: CapabilitiesApiService, useValue: capabilities }] }).compileComponents();
            fixture = TestBed.createComponent(CapturePageComponent);
            fixture.detectChanges();
            await fixture.whenStable();
            fixture.detectChanges();
            expect(boxes()).toHaveLength(0);
        });

        it('never navigates without the capability or with nothing picked', async () => {
            await load(2, CAPABILITIES_UNAVAILABLE);
            const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            fixture.componentInstance.createMaterials();
            expect(navigate).not.toHaveBeenCalled();
            TestBed.resetTestingModule();
            await TestBed.configureTestingModule({ imports: [CapturePageComponent], providers: [provideRouter([]),
                { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
                { provide: OwnDecksApiService, useValue: { detail: () => of(deck) } }, { provide: AuthoringApiService, useValue: api },
                { provide: CapabilitiesApiService, useValue: capabilities }] }).compileComponents();
            capabilities.read.mockReturnValue(of(aiOn));
            fixture = TestBed.createComponent(CapturePageComponent);
            fixture.detectChanges();
            await fixture.whenStable();
            const second = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
            fixture.componentInstance.createMaterials();
            expect(second).not.toHaveBeenCalled();
        });

        it('drops a picked note that leaves the list, and clears the picks with Escape and with «Снять выбор»', async () => {
            const notes = await load(3);
            pick(boxes()[0]!);
            pick(boxes()[1]!);
            api.deleteCapture.mockReturnValue(of(void 0));
            fixture.componentInstance.deleteNote(notes[0]!);
            fixture.detectChanges();
            expect(counter()).toBe('Выбрано: 1');
            boxes()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            fixture.detectChanges();
            expect(counter()).toBe('Выбрано: 0');
            expect(bar()).toBeNull();
            pick(boxes()[0]!);
            const quiet = [...root().querySelectorAll<HTMLButtonElement>('.selection-bar button')].find(button => button.textContent!.trim() === 'Снять выбор')!;
            quiet.click();
            fixture.detectChanges();
            expect(counter()).toBe('Выбрано: 0');
            expect(bar()).toBeNull();
        });

        it('puts focus on «Выбрать все загруженные» when the bar and its button go away', async () => {
            await load(2);
            document.body.append(root());
            pick(boxes()[0]!);
            const quiet = [...root().querySelectorAll<HTMLButtonElement>('.selection-bar button')].find(button => button.textContent!.trim() === 'Снять выбор')!;
            quiet.focus();
            quiet.click();
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(headerBox());
            pick(boxes()[0]!);
            boxes()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(headerBox());
            root().remove();
        });

        it('keeps the picks when Escape is pressed while typing in the note field or outside the list', async () => {
            await load(2);
            pick(boxes()[0]!);
            const field = root().querySelector<HTMLTextAreaElement>('#capture-text')!;
            field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            fixture.detectChanges();
            expect(counter()).toBe('Выбрано: 1');
            root().querySelector('h1')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            fixture.detectChanges();
            expect(counter()).toBe('Выбрано: 1');
        });
    });
});

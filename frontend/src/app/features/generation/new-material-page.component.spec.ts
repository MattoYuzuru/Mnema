import { ChangeDetectionStrategy, Component, forwardRef, input, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { PageTransition } from '../../shared/page-transition.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { CaptureNote } from '../authoring/authoring.models';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemEditorPageComponent } from '../authoring/item-editor-page.component';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { GenerationComposerComponent } from './generation-composer.component';
import { NewMaterialPageComponent, canLeaveNewMaterial } from './new-material-page.component';
import { SessionDetail, parseSessionDetail } from './generation.models';
import { deckFixture, examples, ids, noteIds } from './generation-test-data';
import { ComposerSource } from './note-sources';

@Component({ selector: 'app-generation-composer', template: '<h1 tabindex="-1">Что будем учить сегодня?</h1><p class="composer-stub">{{ deckTitle() }}|{{ capabilities().imageSearch.available }}</p><ul>@for (source of sources(); track source.label) { <li class="chip-stub">{{ source.label }}</li> }</ul>',
    changeDetection: ChangeDetectionStrategy.OnPush })
class ComposerStub {
    readonly deckId = input.required<string>();
    readonly deckTitle = input<string | null>(null);
    readonly capabilities = input.required<LearningCapabilities>();
    readonly sources = input<readonly ComposerSource[]>([]);
    readonly created = output<SessionDetail>();
    readonly sourceRemoved = output<ComposerSource>();
}

// The page finds its editor by the real class: the stub answers to that token.
@Component({ selector: 'app-item-editor-page', template: '<p class="editor-stub">editor</p>', changeDetection: ChangeDetectionStrategy.OnPush,
    providers: [{ provide: ItemEditorPageComponent, useExisting: forwardRef(() => EditorStub) }] })
class EditorStub {
    keep = true;
    confirmLeave(): boolean { return this.keep; }
}

describe('NewMaterialPageComponent', () => {
    let fixture: ComponentFixture<NewMaterialPageComponent>;
    let capabilities: SpyObj<CapabilitiesApiService>;
    let decks: SpyObj<OwnDecksApiService>;
    let authoring: SpyObj<AuthoringApiService>;
    let router: Router;
    let transition: { navigate: ReturnType<typeof vi.fn> };
    let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
    let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const on: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null }, imageSearch: { available: true, reason: null } };

    function create(options: { caps?: unknown; queryParams?: Record<string, string>; deck?: unknown; read?: (noteId: string) => unknown } = {}): void {
        TestBed.resetTestingModule();
        capabilities = spyObj<CapabilitiesApiService>({ read: vi.fn() });
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn() });
        authoring = spyObj<AuthoringApiService>({ readCapture: vi.fn() });
        authoring.readCapture.mockImplementation(((noteId: string) => options.read?.(noteId) ?? of(noteOf(noteId))) as never);
        transition = { navigate: vi.fn().mockResolvedValue(true) };
        capabilities.read.mockReturnValue((options.caps ?? of(on)) as never);
        decks.detail.mockReturnValue((options.deck ?? of(deckFixture)) as never);
        query = new BehaviorSubject(convertToParamMap(options.queryParams ?? {}));
        params = new BehaviorSubject(convertToParamMap({ deckId: ids.deckId }));
        TestBed.configureTestingModule({ providers: [
            { provide: CapabilitiesApiService, useValue: capabilities }, { provide: OwnDecksApiService, useValue: decks },
            { provide: PageTransition, useValue: transition }, { provide: AuthoringApiService, useValue: authoring },
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: ids.deckId }), queryParamMap: query.value },
                paramMap: params, queryParamMap: query } }] });
        TestBed.overrideComponent(NewMaterialPageComponent, { remove: { imports: [GenerationComposerComponent, ItemEditorPageComponent] },
            add: { imports: [ComposerStub, EditorStub] } });
        router = TestBed.inject(Router);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
        fixture = TestBed.createComponent(NewMaterialPageComponent);
        fixture.detectChanges();
    }

    const noteOf = (noteId: string, patch: Partial<CaptureNote> = {}): CaptureNote => ({ noteId, deckId: ids.deckId, rowVersion: '3', source: 'manual',
        text: `Текст ${noteId.slice(-1)}`, contentBytes: 12, archived: false, createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-01T09:00:00Z',
        conversion: null, ...patch });
    const chips = (): string[] => [...root().querySelectorAll('.chip-stub')].map(chip => chip.textContent!);

    it('shows nothing of either page until the capability answer is in, because the editor creates a draft as it opens', () => {
        create({ caps: new Subject() });
        expect(root().querySelector('.composer-stub, .editor-stub')).toBeNull();
        expect(root().querySelector('h1')?.textContent).toBe('Новый материал');
        expect(root().textContent).toContain('Готовим страницу…');
    });

    it('opens the composer where AI generation is available, with the deck title and the capabilities', () => {
        create();
        expect(root().querySelector('.composer-stub')?.textContent).toBe('Японский N4|true');
        expect(root().querySelector('.editor-stub')).toBeNull();
        expect(root().querySelector('.ai-note')).toBeNull();
    });

    it('opens the editor with a calm note when it is not available, and when the answer cannot be read (fail closed)', () => {
        create({ caps: of(CAPABILITIES_UNAVAILABLE) });
        expect(root().querySelector('.editor-stub')).not.toBeNull();
        expect(root().querySelector('.composer-stub')).toBeNull();
        expect(root().querySelector('.ai-note')?.getAttribute('role')).toBe('status');
        expect(root().querySelector('.ai-note')?.textContent).toContain('Помощник Мнема сейчас недоступен');
        create({ caps: throwError(() => new Error('down')) });
        expect(root().querySelector('.editor-stub')).not.toBeNull();
        expect(root().querySelector('.ai-note')).not.toBeNull();
    });

    it('opens the editor without the note when the user chose it, and for a draft handed over from the Workshop', () => {
        create({ queryParams: { write: '1' } });
        expect(root().querySelector('.editor-stub')).not.toBeNull();
        expect(root().querySelector('.ai-note')).toBeNull();
        create({ queryParams: { draft: 'd4af7000-0000-4000-8000-000000000001' } });
        expect(root().querySelector('.editor-stub')).not.toBeNull();
        create({ caps: of(CAPABILITIES_UNAVAILABLE), queryParams: { write: '1' } });
        expect(root().querySelector('.ai-note')).toBeNull();
    });

    it('switches between the composer and the editor when the URL changes (the link «Или откройте пустой редактор»)', () => {
        create();
        query.next(convertToParamMap({ write: '1' }));
        fixture.detectChanges();
        expect(root().querySelector('.editor-stub')).not.toBeNull();
        query.next(convertToParamMap({}));
        fixture.detectChanges();
        expect(root().querySelector('.composer-stub')).not.toBeNull();
    });

    it('still opens the composer when the deck title cannot be read', () => {
        create({ deck: throwError(() => new Error('down')) });
        expect(root().querySelector('.composer-stub')?.textContent).toBe('|true');
    });

    it('starts over for another deck when the deck parameter changes under the reused page', () => {
        create();
        const other = '22222222-2222-4222-8222-222222222222';
        params.next(convertToParamMap({ deckId: other }));
        fixture.detectChanges();
        expect(decks.detail).toHaveBeenLastCalledWith(other);
        expect(capabilities.read).toHaveBeenCalledTimes(2);
        expect(fixture.componentInstance.deckId()).toBe(other);
    });

    it('moves on to the Workshop of a session the composer created', () => {
        create();
        const session = parseSessionDetail(examples['sessionDetailCreated']);
        fixture.debugElement.query(el => el.name === 'app-generation-composer').componentInstance.created.emit(session);
        expect(transition.navigate).toHaveBeenCalledWith(['/decks', ids.deckId, 'workshop', ids.sessionId]);
    });

    describe('focus', () => {
        it('moves it to the new heading when the heading of the loading page it was on is gone', async () => {
            const answer = new Subject<LearningCapabilities>();
            create({ caps: answer });
            document.body.append(root());
            root().querySelector<HTMLElement>('h1')!.focus();
            expect(document.activeElement?.textContent).toBe('Новый материал');
            answer.next(on);
            answer.complete();
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(root().querySelector('h1'));
            expect(document.activeElement?.textContent).toBe('Что будем учить сегодня?');
            root().remove();
        });

        it('leaves it alone when the user has put it somewhere else', async () => {
            create({ caps: new Subject() });
            const other = document.createElement('button');
            document.body.append(other);
            other.focus();
            document.body.append(root());
            (fixture.componentInstance as unknown as { capabilities: { set(value: LearningCapabilities): void } }).capabilities.set(on);
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(other);
            other.remove();
            root().remove();
        });
    });

    it('lets the editor decide whether the page can be left, and leaves freely from the composer', () => {
        create();
        expect(fixture.componentInstance.confirmLeave()).toBe(true);
        expect(canLeaveNewMaterial(fixture.componentInstance)).toBe(true);
        create({ queryParams: { write: '1' } });
        const editor = fixture.debugElement.query(el => el.name === 'app-item-editor-page').componentInstance as EditorStub;
        editor.keep = false;
        expect(canLeaveNewMaterial(fixture.componentInstance)).toBe(false);
    });

    describe('notes picked on «На потом» (#290)', () => {
        const withNotes = (...list: string[]) => ({ queryParams: { notes: list.join(',') } });
        const fourth = '20700000-0000-4000-8000-000000000004';

        it('reads every note again and hands them to the composer as chips with the pin that is current now', () => {
            create(withNotes(noteIds.first, noteIds.second));
            expect(authoring.readCapture.mock.calls.map(call => call[0])).toEqual([noteIds.first, noteIds.second]);
            expect(chips()).toEqual(['Текст 1', 'Текст 2']);
            expect(fixture.componentInstance.noteSources().map(source => source.spec)).toEqual([
                { role: 'SOURCE', type: 'NOTE', noteId: noteIds.first, noteRowVersion: '3' },
                { role: 'SOURCE', type: 'NOTE', noteId: noteIds.second, noteRowVersion: '3' }]);
            expect(root().querySelector('.ai-note')).toBeNull();
        });

        it('names an archived note, one from another deck and one that is gone, keeps the rest and says nothing without notes', () => {
            create({ queryParams: { notes: [noteIds.first, noteIds.second, noteIds.third, fourth].join(',') }, read: noteId => noteId === noteIds.second
                ? of(noteOf(noteId, { archived: true })) : noteId === noteIds.third ? of(noteOf(noteId, { deckId: '99999999-9999-4999-8999-999999999999' }))
                    : noteId === fourth ? throwError(() => new Error('gone')) : undefined });
            expect(chips()).toEqual(['Текст 1']);
            const notice = root().querySelector('.ai-note')?.textContent ?? '';
            expect(notice).toContain('Одна заметка уже в архиве.');
            expect(notice).toContain('Одна заметка из другой колоды.');
            expect(notice).toContain('Одну заметку не удалось найти.');
            expect(notice).toContain('остальные заметки на месте');
            create();
            expect(authoring.readCapture).not.toHaveBeenCalled();
            expect(chips()).toEqual([]);
        });

        it('counts a malformed id as not found, folds a repeated one into one note and never asks the server about either', () => {
            create({ queryParams: { notes: [noteIds.first, noteIds.first, 'not-an-id'].join(',') } });
            expect(authoring.readCapture).toHaveBeenCalledTimes(1);
            expect(chips()).toEqual(['Текст 1']);
            expect(root().querySelector('.ai-note')?.textContent).toContain('Одну заметку не удалось найти.');
        });

        it('takes a removed chip off the list and out of the address, and the last one removes the parameter', () => {
            create(withNotes(noteIds.first, noteIds.second));
            const composer = fixture.debugElement.query(el => el.name === 'app-generation-composer').componentInstance as ComposerStub;
            composer.sourceRemoved.emit(fixture.componentInstance.noteSources()[0]!);
            fixture.detectChanges();
            expect(chips()).toEqual(['Текст 2']);
            expect(vi.mocked(router.navigate).mock.lastCall![1]).toMatchObject({ replaceUrl: true, queryParamsHandling: 'merge',
                queryParams: { notes: noteIds.second } });
            composer.sourceRemoved.emit(fixture.componentInstance.noteSources()[0]!);
            fixture.detectChanges();
            expect(chips()).toEqual([]);
            expect(vi.mocked(router.navigate).mock.lastCall![1]).toMatchObject({ queryParams: { notes: null } });
        });

        it('does not read the notes again when only the address changes under the page, and the editor path ignores them', () => {
            create(withNotes(noteIds.first));
            authoring.readCapture.mockClear();
            query.next(convertToParamMap({ notes: noteIds.first, write: '1' }));
            fixture.detectChanges();
            expect(authoring.readCapture).not.toHaveBeenCalled();
            expect(root().querySelector('.editor-stub')).not.toBeNull();
        });
    });
});

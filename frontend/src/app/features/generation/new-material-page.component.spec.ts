import { ChangeDetectionStrategy, Component, forwardRef, input, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject, Subject, of, throwError } from 'rxjs';

import { PageTransition } from '../../shared/page-transition.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemEditorPageComponent } from '../authoring/item-editor-page.component';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { GenerationComposerComponent } from './generation-composer.component';
import { NewMaterialPageComponent, canLeaveNewMaterial } from './new-material-page.component';
import { SessionDetail, parseSessionDetail } from './generation.models';
import { deckFixture, examples, ids } from './generation-test-data';

@Component({ selector: 'app-generation-composer', template: '<h1 tabindex="-1">Что будем учить сегодня?</h1><p class="composer-stub">{{ deckTitle() }}|{{ capabilities().imageSearch.available }}</p>',
    changeDetection: ChangeDetectionStrategy.OnPush })
class ComposerStub {
    readonly deckId = input.required<string>();
    readonly deckTitle = input<string | null>(null);
    readonly capabilities = input.required<LearningCapabilities>();
    readonly created = output<SessionDetail>();
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
    let transition: { navigate: ReturnType<typeof vi.fn> };
    let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
    let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const on: LearningCapabilities = { ...CAPABILITIES_UNAVAILABLE, aiGeneration: { available: true, reason: null }, imageSearch: { available: true, reason: null } };

    function create(options: { caps?: unknown; queryParams?: Record<string, string>; deck?: unknown } = {}): void {
        TestBed.resetTestingModule();
        capabilities = spyObj<CapabilitiesApiService>({ read: vi.fn() });
        decks = spyObj<OwnDecksApiService>({ detail: vi.fn() });
        transition = { navigate: vi.fn().mockResolvedValue(true) };
        capabilities.read.mockReturnValue((options.caps ?? of(on)) as never);
        decks.detail.mockReturnValue((options.deck ?? of(deckFixture)) as never);
        query = new BehaviorSubject(convertToParamMap(options.queryParams ?? {}));
        params = new BehaviorSubject(convertToParamMap({ deckId: ids.deckId }));
        TestBed.configureTestingModule({ providers: [
            { provide: CapabilitiesApiService, useValue: capabilities }, { provide: OwnDecksApiService, useValue: decks },
            { provide: PageTransition, useValue: transition },
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: ids.deckId }), queryParamMap: query.value },
                paramMap: params, queryParamMap: query } }] });
        TestBed.overrideComponent(NewMaterialPageComponent, { remove: { imports: [GenerationComposerComponent, ItemEditorPageComponent] },
            add: { imports: [ComposerStub, EditorStub] } });
        fixture = TestBed.createComponent(NewMaterialPageComponent);
        fixture.detectChanges();
    }

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
});

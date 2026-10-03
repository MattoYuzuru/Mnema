import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { DeckWorkshopsComponent } from './deck-workshops.component';
import { GenerationApiService } from './generation-api.service';
import { SessionSummary, parseSessionSummary } from './generation.models';
import { clone, examples, ids } from './generation-test-data';

describe('DeckWorkshopsComponent', () => {
    let fixture: ComponentFixture<DeckWorkshopsComponent>;
    let api: SpyObj<GenerationApiService>;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const session = (suffix: string, overrides: Record<string, unknown> = {}): SessionSummary =>
        parseSessionSummary({ ...clone(examples['sessionSummary']), sessionId: `5e550000-0000-4000-8000-${suffix.padStart(12, '0')}`, ...overrides });

    function create(result: unknown): void {
        TestBed.resetTestingModule();
        api = spyObj<GenerationApiService>({ listSessions: vi.fn() });
        api.listSessions.mockReturnValue(result as never);
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api }] });
        fixture = TestBed.createComponent(DeckWorkshopsComponent);
        fixture.componentRef.setInput('deckId', ids.deckId);
        fixture.detectChanges();
    }

    it('asks for the active Workshops of the deck and shows «Мастерская: N активных» with a link to each, and what each is doing', () => {
        create(of({ items: [session('1'), session('2', { approvableCount: 0 })], nextCursor: null }));
        expect(api.listSessions).toHaveBeenCalledWith(ids.deckId, { active: true });
        expect(root().querySelector('h2')?.textContent).toBe('Мастерская: 2 активные');
        const links = [...root().querySelectorAll<HTMLAnchorElement>('li a')];
        expect(links.map(link => link.getAttribute('href'))).toEqual([
            `/decks/${ids.deckId}/workshop/5e550000-0000-4000-8000-000000000001`, `/decks/${ids.deckId}/workshop/5e550000-0000-4000-8000-000000000002`]);
        expect(links[0]!.textContent).toMatch(/Мастерская от \d+ [а-я]+/u);
        expect(root().querySelector('.progress')?.textContent).toContain('6\u00a0готово');
        expect(root().querySelector('section')?.getAttribute('aria-labelledby')).toBe('deck-workshops-title');
    });

    it('names an exercise Workshop as such (AI-13)', () => {
        create(of({ items: [session('1', { kind: 'EXERCISES' }), session('2')], nextCursor: null }));
        const links = [...root().querySelectorAll<HTMLAnchorElement>('li a')].map(link => link.textContent!);
        expect(links[0]).toMatch(/^Мастерская упражнений от /u);
        expect(links[1]).toMatch(/^Мастерская от /u);
    });

    it('names a revision of a material or of an exercise as such (AI-16)', () => {
        create(of({ items: [session('1', { kind: 'REVISE_ITEM' }), session('2', { kind: 'REVISE_EXERCISE' })], nextCursor: null }));
        const links = [...root().querySelectorAll<HTMLAnchorElement>('li a')].map(link => link.textContent!);
        expect(links[0]).toMatch(/^Правка материала от /u);
        expect(links[1]).toMatch(/^Правка упражнения от /u);
    });

    it('declines the Russian plural: 1 активная, 2 активные, 5 активных, 11 активных, 21 активная', () => {
        const heading = (count: number): string => {
            create(of({ items: Array.from({ length: count }, (_, position) => session(String(position + 1))), nextCursor: null }));
            return root().querySelector('h2')!.textContent!;
        };
        expect(heading(1)).toBe('Мастерская: 1 активная');
        expect(heading(3)).toBe('Мастерская: 3 активные');
        expect(heading(5)).toBe('Мастерская: 5 активных');
        expect(heading(11)).toBe('Мастерская: 11 активных');
        expect(heading(21)).toBe('Мастерская: 21 активная');
    });

    it('says «N+» when the first page is full', () => {
        create(of({ items: [session('1'), session('2')], nextCursor: 'more' }));
        expect(root().querySelector('h2')?.textContent).toBe('Мастерская: 2+ активных');
    });

    it('shows nothing when no Workshop is active, and nothing when the list cannot be read', () => {
        create(of({ items: [], nextCursor: null }));
        expect(root().querySelector('section')).toBeNull();
        create(throwError(() => new Error('down')));
        expect(root().querySelector('section')).toBeNull();
    });

    it('asks again for another deck', () => {
        create(of({ items: [session('1')], nextCursor: null }));
        fixture.componentRef.setInput('deckId', '22222222-2222-4222-8222-222222222222');
        fixture.detectChanges();
        expect(api.listSessions).toHaveBeenCalledTimes(2);
        expect(api.listSessions.mock.calls[1]![0]).toBe('22222222-2222-4222-8222-222222222222');
    });
});

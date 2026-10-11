import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { AutoLoadComponent } from '../../shared/auto-load.component';
import { PublicDeckApiService } from './public-deck-api.service';
import { PublicDeckFailure, PublicExercise, PublicExercisePage, PublicMaterial, PublicMaterialPage } from './public-deck.models';
import { PublicExercisesListComponent } from './public-exercises-list.component';
import { PublicMaterialsListComponent } from './public-materials-list.component';

const CODE = 'Kq7xT3mNpR';
const key = (index: number) => `7f1c2d3e-4a5b-4c6d-8e7f-${String(index).padStart(12, '0')}`;

function material(index: number, title = `Материал ${index + 1} о глаголах`): PublicMaterial {
    return { memberKey: key(index), itemRevisionId: key(index + 500), ordinal: index, title };
}
function materialPage(from: number, count: number, nextCursor: string | null, total = 5): PublicMaterialPage {
    return { code: CODE, total, items: Array.from({ length: count }, (_, offset) => material(from + offset)), nextCursor };
}
function exercise(index: number, patch: Partial<PublicExercise> = {}): PublicExercise {
    return { exerciseId: key(index + 100), exerciseRevisionId: key(index + 200), ordinal: index, type: 'FREE_RESPONSE', enabled: true,
        prompt: `Как сказать «я дома»? ${index}`, ...patch };
}
function exercisePage(items: readonly PublicExercise[], nextCursor: string | null = null): PublicExercisePage {
    return { code: CODE, total: items.length, exercises: items, nextCursor };
}

describe('public deck lists', () => {
    let api: { materials: ReturnType<typeof vi.fn>; exercises: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        api = { materials: vi.fn(), exercises: vi.fn() };
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: PublicDeckApiService, useValue: api }] });
    });

    function mount<T extends PublicMaterialsListComponent | PublicExercisesListComponent>(type: new () => T): ComponentFixture<T> {
        const fixture = TestBed.createComponent(type);
        fixture.componentRef.setInput('code', CODE);
        fixture.detectChanges();
        return fixture;
    }
    const root = (fixture: ComponentFixture<unknown>) => fixture.nativeElement as HTMLElement;
    const loadNext = (fixture: ComponentFixture<unknown>) => fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.loadNext.emit();
    const titles = (fixture: ComponentFixture<unknown>) => [...root(fixture).querySelectorAll('.row-link')].map(link => link.textContent);

    describe('materials', () => {
        it('lists the first page as links that open the material in the page, with no page buttons', () => {
            api.materials.mockReturnValue(of(materialPage(0, 2, 'c1')));
            const fixture = mount(PublicMaterialsListComponent);
            expect(api.materials).toHaveBeenCalledWith(CODE, null);
            expect(root(fixture).querySelector('h2')?.textContent).toContain('Материалы');
            expect(root(fixture).querySelector('h2 .count')?.textContent).toBe('· 5');
            expect(titles(fixture)).toEqual(['Материал 1 о глаголах', 'Материал 2 о глаголах']);
            const link = root(fixture).querySelector<HTMLAnchorElement>('.row-link')!;
            expect(link.getAttribute('href')).toBe(`/?material=${key(0)}`);
            expect(root(fixture).querySelector('ol[role=list]')).not.toBeNull();
            expect([...root(fixture).querySelectorAll('button')].map(button => button.textContent)).toEqual([]);
            expect(root(fixture).querySelector('app-auto-load')).not.toBeNull();
        });

        it('continues with the cursor when the auto-load element asks, appends by key without duplicates and ends', () => {
            api.materials.mockReturnValueOnce(of(materialPage(0, 2, 'c1'))).mockReturnValueOnce(of(materialPage(1, 3, null)));
            const fixture = mount(PublicMaterialsListComponent);
            loadNext(fixture);
            fixture.detectChanges();
            expect(api.materials).toHaveBeenLastCalledWith(CODE, 'c1');
            expect(titles(fixture)).toHaveLength(4);
            expect(titles(fixture).at(-1)).toBe('Материал 4 о глаголах');
            expect(fixture.debugElement.query(By.directive(AutoLoadComponent)).componentInstance.continuation()).toBeNull();
        });

        it('starts again from the first page when the cursor is stale (412) and says so', () => {
            api.materials.mockReturnValueOnce(of(materialPage(0, 2, 'old')))
                .mockReturnValueOnce(throwError(() => new PublicDeckFailure('stale')))
                .mockReturnValueOnce(of(materialPage(0, 2, 'fresh', 6)));
            const fixture = mount(PublicMaterialsListComponent);
            loadNext(fixture);
            fixture.detectChanges();
            expect(api.materials).toHaveBeenLastCalledWith(CODE, null);
            expect(titles(fixture)).toHaveLength(2);
            expect(root(fixture).querySelector('h2 .count')?.textContent).toBe('· 6');
            const status = root(fixture).querySelector('p.status[role=status]')!;
            expect(status.textContent).toContain('Список загружен заново с начала');
            expect(status.classList.contains('notice')).toBe(true);
        });

        it('keeps one polite status container in the page, empty until a restart, and brings the head into view after it', () => {
            const scroll = vi.spyOn(Element.prototype, 'scrollIntoView');
            api.materials.mockReturnValueOnce(of(materialPage(0, 2, 'old')))
                .mockReturnValueOnce(throwError(() => new PublicDeckFailure('stale')))
                .mockReturnValueOnce(of(materialPage(0, 2, null)));
            const fixture = mount(PublicMaterialsListComponent);
            const status = root(fixture).querySelector('p.status[role=status]')!;
            expect(status.textContent).toBe('');
            expect(status.classList.contains('notice')).toBe(false);
            loadNext(fixture);
            fixture.detectChanges();
            expect(root(fixture).querySelector('p.status[role=status]')).toBe(status);
            expect(status.textContent).toContain('Автор обновил колоду');
            fixture.detectChanges();
            expect(scroll).toHaveBeenCalledWith({ block: 'start' });
            expect(scroll.mock.contexts.some(element => (element as Element).id === 'public-materials-heading')).toBe(true);
        });

        it('puts the focus on the row of a material, and says when it has no such row', () => {
            api.materials.mockReturnValue(of(materialPage(0, 3, null)));
            const fixture = mount(PublicMaterialsListComponent);
            expect(fixture.componentInstance.focusRow(key(2))).toBe(true);
            expect(document.activeElement).toBe(root(fixture).querySelectorAll('.row-link')[2]);
            expect(fixture.componentInstance.focusRow(key(9))).toBe(false);
        });

        it('keeps the rows when the next page fails and offers one explicit retry', () => {
            api.materials.mockReturnValueOnce(of(materialPage(0, 2, 'c1'))).mockReturnValueOnce(throwError(() => new PublicDeckFailure('unavailable')))
                .mockReturnValueOnce(of(materialPage(2, 1, null)));
            const fixture = mount(PublicMaterialsListComponent);
            loadNext(fixture);
            fixture.detectChanges();
            expect(titles(fixture)).toHaveLength(2);
            const alert = root(fixture).querySelector('[role=alert]')!;
            expect(alert.textContent).toContain('Загруженные остались на месте');
            alert.querySelector('button')!.click();
            fixture.detectChanges();
            expect(titles(fixture)).toHaveLength(3);
        });

        it('says the plain thing for a first page that is refused, and retries', () => {
            api.materials.mockReturnValueOnce(throwError(() => new PublicDeckFailure('rate-limited', 12))).mockReturnValueOnce(of(materialPage(0, 1, null, 1)));
            const fixture = mount(PublicMaterialsListComponent);
            expect(root(fixture).querySelector('[role=alert]')?.textContent).toContain('Слишком много запросов. Подождите 12 с и повторите.');
            root(fixture).querySelector<HTMLButtonElement>('[role=alert] button')!.click();
            fixture.detectChanges();
            expect(titles(fixture)).toHaveLength(1);
        });

        it('reports a deck that is gone or closed to the page, and shows a polite empty state for a deck without materials', () => {
            api.materials.mockReturnValueOnce(throwError(() => new PublicDeckFailure('not-found')));
            const fixture = TestBed.createComponent(PublicMaterialsListComponent);
            const gone = vi.fn();
            fixture.componentInstance.gone.subscribe(gone);
            fixture.componentRef.setInput('code', CODE);
            fixture.detectChanges();
            expect(gone).toHaveBeenCalledTimes(1);
            expect(gone.mock.calls[0][0].kind).toBe('not-found');

            TestBed.resetTestingModule();
            TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: PublicDeckApiService, useValue: api }] });
            api.materials.mockReturnValue(of(materialPage(0, 0, null, 0)));
            const empty = mount(PublicMaterialsListComponent);
            expect(root(empty).querySelector('.empty-state h3')?.textContent).toBe('В этой колоде пока нет материалов');
        });

        it('shows a material without text by its number and ignores the answer of an older list', () => {
            const first = new Subject<PublicMaterialPage>();
            api.materials.mockReturnValueOnce(first);
            const fixture = mount(PublicMaterialsListComponent);
            expect(root(fixture).querySelector('.hint[role=status]')?.textContent).toContain('Загружаем материалы');
            fixture.componentRef.setInput('code', 'Zp4WcA8vQe');
            api.materials.mockReturnValueOnce(of({ ...materialPage(0, 1, null, 1), code: 'Zp4WcA8vQe', items: [material(0, '  ')] }));
            fixture.detectChanges();
            first.next(materialPage(0, 3, null));
            fixture.detectChanges();
            expect(titles(fixture)).toEqual(['Материал 1']);
        });
    });

    describe('exercises', () => {
        it('previews the type and the question, marks a disabled exercise, and shows no answers', () => {
            api.exercises.mockReturnValue(of(exercisePage([
                exercise(0), exercise(1, { type: 'CLOZE', enabled: false }), exercise(2, { type: 'HOLOGRAM', prompt: null })
            ])));
            const fixture = mount(PublicExercisesListComponent);
            expect(root(fixture).querySelector('h2')?.textContent).toContain('Упражнения');
            const rows = [...root(fixture).querySelectorAll('li')];
            expect(rows).toHaveLength(3);
            expect(rows[0].querySelector('.stamp')?.textContent).toContain('Ввести ответ');
            expect(rows[0].querySelector('.prompt')?.textContent).toBe('Как сказать «я дома»? 0');
            expect(rows[1].querySelector('.stamp')?.textContent).toContain('Заполнить пропуски');
            expect(rows[1].textContent).toContain('Выключено автором');
            expect(rows[2].querySelector('.stamp')?.textContent).toContain('Упражнение');
            expect(rows[2].querySelector('.prompt')?.textContent).toBe('Вопрос без текстового описания.');
            expect(root(fixture).querySelector('a, input, textarea')).toBeNull();
            expect(root(fixture).querySelector('.hint')?.textContent).toBe('Показаны только тексты вопросов, без ответов.');
        });

        it('continues with the cursor and restarts after a stale cursor', () => {
            api.exercises.mockReturnValueOnce(of(exercisePage([exercise(0)], 'c1')))
                .mockReturnValueOnce(throwError(() => new PublicDeckFailure('stale')))
                .mockReturnValueOnce(of(exercisePage([exercise(0), exercise(1)])));
            const fixture = mount(PublicExercisesListComponent);
            loadNext(fixture);
            fixture.detectChanges();
            expect(api.exercises).toHaveBeenLastCalledWith(CODE, null);
            expect(root(fixture).querySelectorAll('li')).toHaveLength(2);
            expect(root(fixture).querySelector('p.status[role=status]')?.textContent).toContain('Список загружен заново');
        });

        it('shows a busy service as a retry notice', () => {
            api.exercises.mockReturnValueOnce(throwError(() => new PublicDeckFailure('busy', 1))).mockReturnValueOnce(of(exercisePage([exercise(0)])));
            const fixture = mount(PublicExercisesListComponent);
            expect(root(fixture).querySelector('[role=alert]')?.textContent).toContain('Сейчас много читателей');
            root(fixture).querySelector<HTMLButtonElement>('[role=alert] button')!.click();
            fixture.detectChanges();
            expect(root(fixture).querySelectorAll('li')).toHaveLength(1);
        });
    });
});

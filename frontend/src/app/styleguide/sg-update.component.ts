import { ChangeDetectionStrategy, Component, DestroyRef, ViewEncapsulation, inject, signal } from '@angular/core';

import { NativeDocument } from '../content/native-document';
import { DEMO_ASSETS } from '../content/exercise/demo/demo-media';
import { documentOf, nativeNode } from '../content/rendering/native-renderer.fixtures';
import { diffLines } from '../content/word-diff';
import { CompareColumnsComponent } from '../shared/compare-columns.component';
import { ComparedDocumentComponent } from '../shared/compared-document.component';
import { UPDATE_TEXT, UpdateChoice } from '../shared/copy-text';
import { CountTab, CountTabsComponent } from '../shared/count-tabs.component';
import { UnavailableNoteComponent } from '../shared/unavailable-note.component';
import { UpdateBadgeComponent } from '../shared/update-badge.component';
import { HistoryUnitView, UpdateHistoryUnitComponent } from '../shared/update-history-unit.component';
import { UpdateNewsComponent } from '../shared/update-news.component';
import { UpdateCounts, UpdateSummaryComponent } from '../shared/update-summary.component';
import { UpdateUnitRowComponent, UpdateUnitView } from '../shared/update-unit-row.component';
import { WordDiffComponent } from '../shared/word-diff.component';
import { SgSpecimenComponent } from './sg-specimen.component';

type ClassTab = 'new' | 'fix' | 'conflict' | 'deleted';

const id = (index: number): string => `e5000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}`;
/** A paragraph with fixed node ids, so the same paragraph of two versions is the same node, as it is on the server. */
const paragraph = (index: number, text: string): ReturnType<typeof nativeNode> =>
    nativeNode('paragraph', {}, [nativeNode('text', { text, marks: [] }, [], { id: id(index + 1000) })], { id: id(index) });
const image = (index: number): ReturnType<typeof nativeNode> =>
    nativeNode('image', { assetId: DEMO_ASSETS.waveDense, alt: 'Схема звуковой волны: колебания частые' }, [], { id: id(index) });

/** «Материал, изменённый автором»: a paragraph the author edited, one he did not touch, and an image he inserted. */
const MINE_MATERIAL: NativeDocument = documentOf([
    paragraph(1, 'Глагол ser описывает постоянные свойства: профессию, национальность и время.'),
    paragraph(2, 'Глагол estar описывает состояние и место.')
]);
const AUTHOR_MATERIAL: NativeDocument = documentOf([
    paragraph(1, 'Глагол ser описывает постоянные свойства: профессию, национальность, время и родство.'),
    paragraph(2, 'Глагол estar описывает состояние и место.'),
    image(3)
]);

/** «Материал, который автор убрал из середины»: a block exists only on the reader's side. */
const MINE_REMOVED: NativeDocument = documentOf([
    paragraph(11, 'Простое прошедшее время называют pretérito indefinido.'),
    paragraph(12, 'Устаревшее замечание о произношении.')
]);
const AUTHOR_REMOVED: NativeDocument = documentOf([paragraph(11, 'Простое прошедшее время называют pretérito indefinido.')]);

const NEW_MATERIAL: NativeDocument = documentOf([
    paragraph(21, 'Повелительное наклонение: tú hablas становится «¡habla!», а vosotros hablaréis становится «¡hablad!».')
]);

const EXERCISE_MINE = ['Вставьте глагол: Yo ___ estudiante (ser).'];
const EXERCISE_AUTHOR = ['Вставьте нужную форму глагола: Yo ___ estudiante de medicina (ser).'];
const DESCRIPTION_MINE = ['Девять самых частых испанских глаголов с примерами для начинающих.'];
const DESCRIPTION_AUTHOR = ['Двадцать самых частых испанских глаголов с примерами и проверкой на слух.'];

/** «Обновление копии» (Updates/5): the elements of the review screen of an update, with local fixtures and no backend. */
@Component({
    selector: 'app-sg-update',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, UpdateBadgeComponent, UnavailableNoteComponent, UpdateSummaryComponent, CountTabsComponent, CompareColumnsComponent,
        ComparedDocumentComponent, WordDiffComponent, UpdateUnitRowComponent, UpdateNewsComponent, UpdateHistoryUnitComponent],
    templateUrl: './sg-update.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgUpdateComponent {
    private readonly destroyRef = inject(DestroyRef);
    private timer: ReturnType<typeof setTimeout> | null = null;

    protected readonly mineMaterial = MINE_MATERIAL;
    protected readonly authorMaterial = AUTHOR_MATERIAL;
    protected readonly mineRemoved = MINE_REMOVED;
    protected readonly authorRemoved = AUTHOR_REMOVED;
    protected readonly newMaterial = NEW_MATERIAL;
    protected readonly assetSources = { [DEMO_ASSETS.waveDense]: '/assets/demo/wave-dense.svg' };

    protected readonly exerciseDiff = diffLines(EXERCISE_MINE, EXERCISE_AUTHOR, 'ru');
    protected readonly descriptionDiff = diffLines(DESCRIPTION_MINE, DESCRIPTION_AUTHOR, 'ru');
    protected readonly orderMine = ['Спряжение ser и estar', 'Простое прошедшее время', 'Повелительное наклонение'];
    protected readonly orderAuthor = ['Спряжение ser и estar', 'Повелительное наклонение', 'Простое прошедшее время'];

    protected readonly fullCounts: UpdateCounts = { materials: 12, exercises: 30, fixes: 5, conflicts: 2, deleted: 3 };
    protected readonly singleCounts: UpdateCounts = { materials: 1, exercises: 0, fixes: 21, conflicts: 0, deleted: 0 };
    protected readonly riskyCounts: UpdateCounts = { materials: 0, exercises: 0, fixes: 0, conflicts: 2, deleted: 1 };
    protected readonly news = 'Добавили повелительное наклонение и 30 упражнений к нему. Исправили опечатки в спряжении estar.';
    protected readonly accepting = signal(false);
    protected readonly accepted = signal(false);

    protected readonly tabs: readonly CountTab<ClassTab>[] = [
        { value: 'new', label: UPDATE_TEXT.tabs.new, count: 12 },
        { value: 'fix', label: UPDATE_TEXT.tabs.fix, count: 5 },
        { value: 'conflict', label: UPDATE_TEXT.tabs.conflict, count: 2 },
        { value: 'deleted', label: UPDATE_TEXT.tabs.deleted, count: 3 }
    ];
    protected readonly tab = signal<ClassTab>('conflict');
    protected tabLabel(value: ClassTab): string { return UPDATE_TEXT.tabs[value]; }

    protected readonly materialUnit: UpdateUnitView = { id: 'u1', kind: 'material', title: 'Спряжение ser и estar', class: 'fix' };
    protected readonly exerciseUnit: UpdateUnitView = { id: 'u2', kind: 'exercise', title: 'Вставьте глагол: Yo ___ estudiante', class: 'conflict' };
    protected readonly removedUnit: UpdateUnitView = { id: 'u3', kind: 'material', title: 'Простое прошедшее время', class: 'deleted' };
    protected readonly newUnit: UpdateUnitView = { id: 'u4', kind: 'material', title: 'Повелительное наклонение', class: 'new' };
    protected readonly newExerciseUnit: UpdateUnitView = {
        id: 'u5', kind: 'exercise', title: 'Превратите в приказ: tú hablas', class: 'new', dependency: 'Повелительное наклонение'
    };
    protected readonly lockedUnit: UpdateUnitView = { id: 'u-locked', kind: 'material', title: 'Спряжение глагола hablar', class: 'new' };
    protected readonly lockedNote = UPDATE_TEXT.lockedFor('Что значит hablar?');
    protected readonly againUnit: UpdateUnitView = { id: 'u6', kind: 'material', title: 'Неправильные глаголы с корнем на -o-', class: 'changed-again' };
    protected readonly orderUnit: UpdateUnitView = { id: 'u7', kind: 'order', title: 'Порядок материалов', class: 'fix' };
    protected readonly descriptionUnit: UpdateUnitView = { id: 'u8', kind: 'description', title: 'Описание колоды', class: 'fix' };
    protected readonly goalUnit: UpdateUnitView = {
        id: 'u9', kind: 'goal', title: 'Цель: говорить о повседневных делах на уровне A2, не заглядывая в таблицы спряжения', class: 'new'
    };

    protected readonly exerciseChoice = signal<UpdateChoice | null>(null);

    /** «Что нового» (Updates/6 part B): the units of an update as the history lists them, one per change and one per state. */
    protected readonly historyUnits: readonly HistoryUnitView[] = [
        { id: 'h1', kind: 'material', change: 'ADDED', title: 'Повелительное наклонение', state: null },
        { id: 'h2', kind: 'material', change: 'CHANGED', title: 'Спряжение ser и estar', state: null },
        { id: 'h3', kind: 'exercise', change: 'REMOVED', title: 'Вставьте глагол: Yo ___ estudiante', state: null },
        { id: 'h4', kind: 'order', change: 'REORDERED', title: 'Порядок материалов', state: null },
        { id: 'h5', kind: 'description', change: 'CHANGED', title: 'Испанский язык: базовые глаголы', state: null },
        { id: 'h6', kind: 'material', change: 'CHANGED', title: 'Простое прошедшее время', state: 'RETURNED' },
        { id: 'h7', kind: 'exercise', change: 'ADDED', title: 'Превратите в приказ: tú hablas, а потом объясните, почему здесь нужна именно эта форма', state: 'CHANGED_SINCE' }
    ];
    protected readonly confirmText = UPDATE_TEXT.revertAsk.CHANGED('Спряжение ser и estar');
    protected readonly revertText = UPDATE_TEXT.revert;
    protected readonly revertConfirmText = UPDATE_TEXT.revertConfirm;
    protected readonly withText = UPDATE_TEXT.revertWith.CHANGED('«Вставьте глагол: Yo ___ estudiante»');
    protected readonly noRepeatText = UPDATE_TEXT.revertNoRepeat;
    protected readonly cancelText = 'Отмена';
    protected readonly viewUpdate = UPDATE_TEXT.viewUpdate;
    protected readonly newsLook = UPDATE_TEXT.newsLook;
    protected readonly newsDecide = UPDATE_TEXT.newsDecide;

    constructor() { this.destroyRef.onDestroy(() => { if (this.timer !== null) clearTimeout(this.timer); }); }

    protected acceptSafe(): void {
        this.accepted.set(false);
        this.accepting.set(true);
        this.timer = setTimeout(() => { this.accepting.set(false); this.accepted.set(true); }, 1200);
    }
}

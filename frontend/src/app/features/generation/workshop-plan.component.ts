import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, output, signal, untracked, viewChild
} from '@angular/core';

import { Mechanic } from '../../content/exercise/exercise-content.models';
import { SegmentedChoiceComponent, SegmentedOption } from '../../shared/segmented-choice.component';
import { BuilderValue, DEFAULT_BUILDER_VALUE, exercisesCount } from './exercise-builder';
import { ExerciseSettingsFieldsComponent } from './exercise-settings-fields.component';
import { ExercisePlanItem, ExercisesPlan, MaterialPlanItem, MaterialsPlan, PLAN_EFFORTS, PlanEffort } from './generation.models';
import {
    ExerciseDraft, MaterialDraft, PLAN_EFFORT_LABELS, MAX_PLAN_TITLE, describePlanOverLimit, describePlanPaid, describeTotals, draftOf,
    HOLD_LAPSED_NOTE, exercisesTotals, isValidPlanTitle, materialsTotals, reapplyExercises, reapplyMaterials, takeBack, withItem, withoutRow
} from './plan-editor';
import { WorkshopSessionStore } from './workshop-session.store';

/** Published by the sticky bar so the page scrolls a focused control clear of it (WCAG 2.4.11), like the review of exercises. */
const BAR_HEIGHT_PROPERTY = '--mn-bulk-bar-height';
/** The total is said once the owner pauses, not on every tick of a slider. */
const ANNOUNCE_PAUSE_MS = 500;

const EFFORT_CHOICES: readonly SegmentedOption<PlanEffort>[] = PLAN_EFFORTS.map(value => ({ value, label: PLAN_EFFORT_LABELS[value] }));

/**
 * The plan of a plan-first Workshop (AI-14, #295): while Мнема makes it (`PLANNING`) a quiet status and «Отменить»; when it is ready
 * (`PLAN_READY`) a list the owner edits before anything is created: take a row off or back, change the mechanics and the count (or the
 * topic and the effort of a material), and «Запустить по плану». The totals follow every edit, priced from the rates the plan carries; the
 * plan's own cost is shown apart, as already paid. Nothing is clamped silently: a plan over the limits is said so in words, and the server's
 * `422` is explained the same way. The edited plan stays on screen when a launch is refused.
 *
 * The draft is made again from the server's plan only when the session version moves (a poll that finds the same version keeps the edits).
 * When it does move (a launch refused with `412`), the owner's rows are put onto the new plan if everything they kept is still in it; only
 * otherwise are they dropped, and the page says so. The words of a refusal survive that re-read.
 */
@Component({
    selector: 'app-workshop-plan',
    imports: [ExerciseSettingsFieldsComponent, SegmentedChoiceComponent],
    templateUrl: './workshop-plan.component.html',
    styleUrl: './workshop-plan.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class WorkshopPlanComponent {
    /**
     * The plan was launched or cancelled and is about to leave the page, and the button that had focus with it: the page puts focus on its own
     * title (this component is destroyed with the plan, so it cannot do it itself).
     */
    readonly finished = output<void>();

    private readonly store = inject(WorkshopSessionStore);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    protected readonly footer = viewChild<ElementRef<HTMLElement>>('footer');

    protected readonly session = this.store.session;
    protected readonly plan = computed(() => {
        const session = this.session();
        return session !== null && session.state === 'PLAN_READY' && session.plan !== null && !session.plan.approved ? session.plan : null;
    });
    protected readonly exercisesPlan = computed<ExercisesPlan | null>(() => { const plan = this.plan(); return plan?.kind === 'EXERCISES' ? plan : null; });
    protected readonly materialsPlan = computed<MaterialsPlan | null>(() => { const plan = this.plan(); return plan?.kind === 'MATERIALS' ? plan : null; });

    protected readonly exerciseRows = signal<ExerciseDraft>([]);
    protected readonly materialRows = signal<MaterialDraft>([]);
    /** What a removed material had when it was removed: «Вернуть» gives it back as it was. */
    protected readonly removed = signal<ReadonlyMap<string, ExercisePlanItem>>(new Map());
    /** The material rows taken off, newest last: «Вернуть» gives one back as it was. */
    protected readonly removedMaterials = signal<MaterialDraft>([]);
    /** The row whose «Убрать» asked «последнюю строку убрать?» and waits for the answer. */
    protected readonly confirmLast = signal<number | null>(null);
    /** What the live region says: the total, once the owner pauses. The visible total follows every tick. */
    protected readonly announced = signal('');
    /** The failure of the last launch, in words; cleared by the next edit. */
    protected readonly failure = signal<string | null>(null);
    /** The server's plan moved on while the owner had edits: the draft was made again, and this says so. */
    protected readonly refreshed = signal(false);
    protected readonly titleMissing = signal(false);
    protected readonly effortChoices = EFFORT_CHOICES;
    protected readonly maxTitle = MAX_PLAN_TITLE;
    protected readonly exercisesCount = exercisesCount;
    protected readonly holdLapsed = HOLD_LAPSED_NOTE;

    /** The version of the session the draft was made from: the one the launch is pinned to. */
    private readonly draftVersion = signal<string | null>(null);
    private dirty = false;
    private nextRowId = 1_000;
    private announceTimer: ReturnType<typeof setTimeout> | null = null;
    private readonly values = new WeakMap<ExercisePlanItem, BuilderValue>();

    protected readonly busy = computed(() => this.store.busy().has('session'));
    protected readonly paid = computed(() => { const plan = this.plan(); return plan === null ? null : describePlanPaid(plan); });
    protected readonly totals = computed(() => {
        const exercises = this.exercisesPlan();
        const materials = this.materialsPlan();
        if (exercises !== null) return exercisesTotals(exercises, this.exerciseRows());
        return materials !== null ? materialsTotals(this.materialRows()) : null;
    });
    protected readonly totalsText = computed(() => {
        const plan = this.plan();
        const totals = this.totals();
        return plan === null || totals === null ? '' : describeTotals(plan.kind, totals, plan.cost.barCredits);
    });
    protected readonly rowCount = computed(() => this.exercisesPlan() !== null ? this.exerciseRows().length : this.materialRows().length);
    protected readonly overLimit = computed(() => {
        const plan = this.plan();
        if (plan === null) return null;
        return describePlanOverLimit(plan, plan.kind === 'EXERCISES' ? this.exerciseRows() : this.materialRows());
    });
    /** The plan costs more than the hold the server made for it: the launch asks for the difference. */
    protected readonly overHold = computed(() => {
        const plan = this.plan();
        const totals = this.totals();
        return plan !== null && totals !== null && totals.credits > plan.cost.holdCredits;
    });
    /** The materials of the spec that are not in the plan now: taken off by the owner (or never planned). */
    protected readonly offPlan = computed(() => {
        const plan = this.exercisesPlan();
        if (plan === null) return [];
        const kept = new Set(this.exerciseRows().map(row => row.item.memberKey));
        return plan.targets.filter(target => !kept.has(target.memberKey));
    });
    protected readonly allowed = computed<readonly Mechanic[]>(() => this.exercisesPlan()?.allowedMechanics ?? []);
    protected readonly sourceLabels = computed(() => new Map((this.materialsPlan()?.sources ?? []).map(source => [source.noteId, source.label])));

    constructor() {
        // The draft follows the session's version, not its object: a poll that finds the same version keeps what the owner edited.
        const version = computed(() => this.plan() === null ? null : this.session()!.rowVersion);
        effect(() => {
            const next = version();
            untracked(() => this.seed(next));
        });
        // The sticky bar's height becomes scroll padding, so a focused row control is never hidden behind it.
        effect(onCleanup => {
            const element = this.footer()?.nativeElement;
            if (element === undefined) return;
            const root = element.ownerDocument.documentElement;
            const apply = (): void => root.style.setProperty(BAR_HEIGHT_PROPERTY, `${element.offsetHeight}px`);
            apply();
            const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply);
            observer?.observe(element);
            onCleanup(() => { observer?.disconnect(); root.style.removeProperty(BAR_HEIGHT_PROPERTY); });
        });
        this.destroyRef.onDestroy(() => { if (this.announceTimer !== null) clearTimeout(this.announceTimer); });
    }

    private seed(version: string | null): void {
        const plan = this.plan();
        if (version === null || plan === null) { this.draftVersion.set(null); return; }
        const previous = this.draftVersion();
        if (previous === version) return;
        if (previous !== null && this.dirty) {
            // The plan of the same session moved on while the owner edited: their rows stay when nothing they kept has gone from it.
            const kept = plan.kind === 'EXERCISES' ? reapplyExercises(plan, this.exerciseRows()) : reapplyMaterials(plan, this.materialRows());
            if (kept !== null) {
                if (plan.kind === 'EXERCISES') this.exerciseRows.set(kept as ExerciseDraft); else this.materialRows.set(kept as MaterialDraft);
                this.draftVersion.set(version);
                this.refreshed.set(false);
                return;
            }
        }
        this.refreshed.set(previous !== null && this.dirty);
        this.draftVersion.set(version);
        this.dirty = false;
        // The words of a refused launch outlive the re-read that the refusal asked for; only the first draft starts clean.
        if (previous === null) this.failure.set(null);
        this.titleMissing.set(false);
        this.confirmLast.set(null);
        this.removed.set(new Map());
        this.removedMaterials.set([]);
        if (plan.kind === 'EXERCISES') { this.exerciseRows.set(draftOf(plan.items)); this.materialRows.set([]); }
        else { this.materialRows.set(draftOf(plan.items)); this.exerciseRows.set([]); }
        this.nextRowId = 1_000;
        this.announced.set(this.totalsText());
    }

    // --- Exercises ---

    /** The value the mechanics chips of a row take; the same object for the same item, so a row that did not change is not handed a new one. */
    protected valueOf(item: ExercisePlanItem): BuilderValue {
        let value = this.values.get(item);
        if (value === undefined) { value = { ...DEFAULT_BUILDER_VALUE, mechanics: item.mechanics }; this.values.set(item, value); }
        return value;
    }

    protected setMechanics(id: number, value: BuilderValue): void {
        if (value.mechanics.length === 0) return;
        this.exerciseRows.update(rows => withItem(rows, id, { mechanics: value.mechanics }));
        this.edited();
    }

    protected setCount(id: number, event: Event): void {
        const count = Number((event.target as HTMLInputElement).value);
        if (!Number.isInteger(count) || count < 1) return;
        this.exerciseRows.update(rows => withItem(rows, id, { count }));
        // The visible total follows every tick; what is said waits for the end of the move (`change`).
        this.edited(false);
    }

    protected countSettled(): void {
        this.scheduleAnnounce();
    }

    protected countText(count: number): string {
        return exercisesCount(count);
    }

    protected removeExercise(id: number): void {
        const rows = this.exerciseRows();
        const index = rows.findIndex(row => row.id === id);
        const row = rows[index];
        if (row === undefined || this.askLast(id, rows.length)) return;
        this.removed.update(held => new Map(held).set(row.item.memberKey, row.item));
        this.exerciseRows.set(withoutRow(rows, id));
        this.edited();
        this.focusAfterRemoval(index);
    }

    protected bringBack(memberKey: string): void {
        const plan = this.exercisesPlan();
        if (plan === null) return;
        const before = this.exerciseRows();
        const added = takeBack(plan, before, memberKey, this.removed().get(memberKey) ?? null, this.nextRowId);
        if (added === before) return;
        this.nextRowId = added.reduce((highest, row) => Math.max(highest, row.id + 1), this.nextRowId);
        this.exerciseRows.set(added);
        this.edited();
        const last = added[added.length - 1]!;
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(`[data-row="${last.id}"] .row-title`)?.focus(), { injector: this.injector });
    }

    // --- Materials ---

    protected setTitle(id: number, event: Event): void {
        this.materialRows.update(rows => withItem(rows, id, { title: (event.target as HTMLInputElement).value }));
        this.titleMissing.set(false);
        this.edited();
    }

    protected setEffort(id: number, effort: PlanEffort | null): void {
        if (effort === null) return;
        this.materialRows.update(rows => withItem(rows, id, { effort }));
        this.edited();
    }

    protected removeMaterial(id: number): void {
        const rows = this.materialRows();
        const index = rows.findIndex(row => row.id === id);
        const row = rows[index];
        if (row === undefined || this.askLast(id, rows.length)) return;
        this.removedMaterials.update(held => [...held, row]);
        this.materialRows.set(withoutRow(rows, id));
        this.edited();
        this.focusAfterRemoval(index);
    }

    /** A removed material row comes back at the end of the plan, as it was. */
    protected bringBackMaterial(id: number): void {
        const row = this.removedMaterials().find(held => held.id === id);
        if (row === undefined) return;
        this.removedMaterials.update(held => held.filter(candidate => candidate.id !== id));
        const added = { id: this.nextRowId++, item: row.item };
        this.materialRows.update(rows => [...rows, added]);
        this.edited();
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(`[data-row="${added.id}"] .title-input`)?.focus(), { injector: this.injector });
    }

    /** The last row is not taken off at once: without it there is nothing to launch, so the row asks first. */
    protected keepRow(): void {
        const id = this.confirmLast();
        this.confirmLast.set(null);
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(`[data-row="${id}"] .row-remove`)?.focus(), { injector: this.injector });
    }

    private askLast(id: number, count: number): boolean {
        if (count > 1 || this.confirmLast() === id) return false;
        this.confirmLast.set(id);
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(`[data-row="${id}"] [data-keep]`)?.focus(), { injector: this.injector });
        return true;
    }

    protected removedTitle(row: { readonly item: MaterialPlanItem }): string {
        return row.item.title.trim() || 'Без темы';
    }

    protected titleValid(item: MaterialPlanItem): boolean {
        return isValidPlanTitle(item.title);
    }

    protected sourceLabel(item: MaterialPlanItem): string | null {
        return item.source === null ? null : this.sourceLabels().get(item.source) ?? null;
    }

    // --- Commands ---

    protected async launch(): Promise<void> {
        const plan = this.plan();
        const version = this.draftVersion();
        if (plan === null || version === null || this.busy()) return;
        if (this.rowCount() === 0) {
            this.failure.set(plan.kind === 'EXERCISES'
                ? 'В плане не осталось строк. Верните материал из списка ниже или отмените план.' : 'В плане не осталось строк. Отмените план и начните заново.');
            return;
        }
        if (plan.kind === 'MATERIALS' && this.materialRows().some(row => !isValidPlanTitle(row.item.title))) {
            this.titleMissing.set(true);
            this.failure.set('У каждой строки должна быть тема, не длиннее 160 знаков.');
            afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('input[aria-invalid="true"]')?.focus(), { injector: this.injector });
            return;
        }
        this.failure.set(null);
        const rows = plan.kind === 'EXERCISES' ? this.exerciseRows() : this.materialRows();
        const outcome = await this.store.approvePlan(rows, version);
        if (!outcome.ok) this.failure.set(outcome.message); else this.finished.emit();
    }

    protected async cancel(): Promise<void> {
        if (this.busy()) return;
        if (await this.store.cancel()) this.finished.emit();
    }

    private edited(announce = true): void {
        this.dirty = true;
        this.failure.set(null);
        this.refreshed.set(false);
        this.confirmLast.set(null);
        if (announce) this.scheduleAnnounce();
    }

    private scheduleAnnounce(): void {
        if (this.announceTimer !== null) clearTimeout(this.announceTimer);
        this.announceTimer = setTimeout(() => { this.announceTimer = null; this.announced.set(this.totalsText()); }, ANNOUNCE_PAUSE_MS);
    }

    /** The button that had focus is gone: focus goes to the row now at its place, or to the one before it, or to the heading. */
    private focusAfterRemoval(index: number): void {
        afterNextRender(() => {
            const buttons = Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('.row-remove'));
            const next = buttons[Math.min(index, buttons.length - 1)];
            (next ?? this.host.nativeElement.querySelector<HTMLElement>('.plan-title'))?.focus();
        }, { injector: this.injector });
    }
}

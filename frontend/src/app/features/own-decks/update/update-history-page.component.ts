import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, LOCALE_ID, afterNextRender, computed, inject, signal } from '@angular/core';
import { formatDate } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';

import { ToastService } from '../../../core/notifications/toast.service';
import { COPY_TEXT, UPDATE_TEXT } from '../../../shared/copy-text';
import { HistoryUnitView, UpdateHistoryUnitComponent } from '../../../shared/update-history-unit.component';
import { isCanonicalEntityId } from '../own-deck.models';
import { OwnDecksApiService } from '../own-decks-api.service';
import { UpdateHistoryApiService } from './update-history-api.service';
import {
    HISTORY_MAX_LIMIT,
    HistoryEntry,
    HistoryUnit,
    REVERT_MAX_WITH,
    RevertFailure,
    newRevertCommandId,
    relativeWhen,
    revertFailureOf,
    splitUnitId
} from './update-history.models';
import { viewKindOf } from './update-plan.models';

/** One row of an entry, with the words the page needs. */
interface HistoryRow {
    readonly unit: HistoryUnit;
    readonly view: HistoryUnitView;
    /** The unit is `MATERIAL`/`EXERCISE`, exactly as the update made it, in an attached copy: «Вернуть как было» is offered. */
    readonly revertible: boolean;
}

interface HistoryItem {
    readonly entry: HistoryEntry;
    readonly headingId: string;
    readonly when: string;
    readonly rows: readonly HistoryRow[];
}

/**
 * One return of a unit the owner started. `commandId` and `with` stay the same until the outcome is known, so an unknown outcome is retried with the
 * very same command. `group` is the second step: the server named units that come back together with this one and the owner confirms them.
 */
interface RevertFlow {
    readonly entryId: string;
    readonly unitId: string;
    readonly title: string;
    readonly change: 'ADDED' | 'CHANGED' | 'REMOVED';
    readonly commandId: string;
    readonly with: readonly string[];
    readonly group: boolean;
    readonly busy: boolean;
    readonly problem: string | null;
    /** The last request ended without a known outcome. */
    readonly unknown: boolean;
}

const WHEN_FORMAT = 'dd.MM.y, HH:mm';

/**
 * «История обновлений» of a copy (`/decks/:deckId/update/history`, Updates/6 part B): the applies that made a revision of the copy, newest first,
 * with the units each one changed and, for a material or an exercise that is still exactly what the update made it, «Вернуть как было».
 *
 * It is a route of its own, not a section of the review screen: the review screen shows only a closed state (up to date, applying, detached) in
 * most of the cases «Что нового» is about (everything was taken automatically, nothing is left to decide), and its plan is paged and
 * recomputed. The history is one bounded read (the newest 20 entries, 100 units each) that does not depend on the plan.
 *
 * Opening it marks every automatic entry it shows seen (once each, `:seen`), which is what removes the notice «Что нового» from the hub.
 *
 * Returning: the button opens an inline confirmation that says in one sentence what will happen; «Вернуть» sends the command with a fresh
 * `commandId`. A 400 `unit_needed` names the units that go back together with it (a material and the exercises taken with it): the page shows
 * «Вместе с этим вернутся: …» and a second confirmation that sends them in `with` under a new command. 409 `UPDATE_REVERT_STALE` and
 * `FORK_DETACHED` explain themselves and read the history again; an unknown outcome is retried with the same command. After a return the
 * history and the deck are read again.
 */
@Component({
    selector: 'app-update-history-page',
    imports: [RouterLink, UpdateHistoryUnitComponent],
    templateUrl: './update-history-page.component.html',
    styleUrls: ['../own-decks-page.css', './update-history-page.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class UpdateHistoryPageComponent {
    protected readonly text = UPDATE_TEXT;
    protected readonly copyText = COPY_TEXT;

    readonly deckId = signal('');
    readonly deckTitle = signal<string | null>(null);
    readonly detached = signal(false);
    /** The author published something the copy does not have yet (the badge): a link to the review screen is offered. */
    readonly updateWaiting = signal(false);
    readonly phase = signal<'loading' | 'error' | 'ready'>('loading');
    readonly loadFailure = signal<'not-found' | 'other'>('other');
    readonly entries = signal<readonly HistoryEntry[]>([]);
    /** The notice above the list: what a refused or stale return meant. It stays until the next return starts. */
    readonly notice = signal<string | null>(null);
    readonly flow = signal<RevertFlow | null>(null);
    /** The one polite live region of the page. */
    readonly liveMessage = signal('');
    private readonly now = signal(Date.now());

    protected readonly items = computed<readonly HistoryItem[]>(() => this.entries().map((entry, index) => {
        const relative = relativeWhen(entry.appliedAt, this.now());
        const exact = formatDate(entry.appliedAt, WHEN_FORMAT, this.locale);
        return {
            entry, headingId: `history-entry-${index}`, when: relative === null ? exact : `${relative}, ${exact}`,
            rows: entry.units.map(unit => this.rowOf(unit))
        };
    }));

    private readonly route = inject(ActivatedRoute);
    private readonly api = inject(UpdateHistoryApiService);
    private readonly decks = inject(OwnDecksApiService);
    private readonly toasts = inject(ToastService);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    private readonly locale = inject(LOCALE_ID);

    private loadSequence = 0;
    private historyLoad: Subscription | null = null;
    private deckLoad: Subscription | null = null;
    private revertRequest: Subscription | null = null;
    /** The entries already marked seen: each is asked for once. */
    private readonly marked = new Set<string>();

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const deckId = params.get('deckId');
            if (deckId !== null) this.start(deckId.toLowerCase());
        });
        this.destroyRef.onDestroy(() => {
            this.historyLoad?.unsubscribe();
            this.deckLoad?.unsubscribe();
            this.revertRequest?.unsubscribe();
        });
    }

    reload(): void {
        this.loadHistory();
    }

    // -- loading ------------------------------------------------------------------------------

    private start(deckId: string): void {
        this.deckId.set(deckId);
        this.deckTitle.set(null);
        this.detached.set(false);
        this.updateWaiting.set(false);
        this.entries.set([]);
        this.notice.set(null);
        this.flow.set(null);
        this.liveMessage.set('');
        this.marked.clear();
        this.revertRequest?.unsubscribe();
        this.phase.set('loading');
        if (!isCanonicalEntityId(deckId)) {
            this.loadFailure.set('not-found');
            this.phase.set('error');
            return;
        }
        this.loadDeck();
        this.loadHistory();
    }

    private loadDeck(): void {
        this.deckLoad?.unsubscribe();
        this.deckLoad = this.decks.detail(this.deckId()).subscribe({
            next: deck => {
                this.deckTitle.set(deck.metadata.title);
                this.detached.set(deck.fork?.detached ?? false);
                this.updateWaiting.set(deck.fork?.updateAvailable ?? false);
            },
            error: () => undefined
        });
    }

    private loadHistory(): void {
        this.historyLoad?.unsubscribe();
        const sequence = ++this.loadSequence;
        if (this.phase() === 'error') this.phase.set('loading');
        this.historyLoad = this.api.history(this.deckId(), HISTORY_MAX_LIMIT).subscribe({
            next: history => {
                if (sequence !== this.loadSequence) return;
                this.now.set(Date.now());
                this.entries.set(history.entries);
                this.phase.set('ready');
                this.markSeen(history.entries);
            },
            error: error => {
                if (sequence !== this.loadSequence) return;
                this.loadFailure.set(revertFailureOf(error).kind === 'not-found' ? 'not-found' : 'other');
                this.phase.set('error');
            }
        });
    }

    /**
     * An automatic update the owner has now seen listed stops being a notice in the hub. A failure is silent: the notice stays and the next
     * visit asks again. The request is not cancelled with the page: leaving right after the list appeared must still mark it.
     */
    private markSeen(entries: readonly HistoryEntry[]): void {
        const deckId = this.deckId();
        for (const entry of entries) {
            if (entry.mode !== 'AUTO' || entry.seen || this.marked.has(entry.deckRevisionId)) continue;
            this.marked.add(entry.deckRevisionId);
            this.api.markSeen(deckId, entry.deckRevisionId).subscribe({ error: () => this.marked.delete(entry.deckRevisionId) });
        }
    }

    // -- rows ---------------------------------------------------------------------------------

    private rowOf(unit: HistoryUnit): HistoryRow {
        const kind = viewKindOf(unit.kind);
        const title = unit.title !== null && unit.title.trim().length > 0 ? unit.title : UPDATE_TEXT.untitled[kind];
        const state = unit.state === 'RETURNED' || unit.state === 'CHANGED_SINCE' ? unit.state : null;
        return { unit, view: { id: unit.unitId, kind, change: unit.change, title, state }, revertible: unit.revertible };
    }

    protected flowFor(entry: HistoryEntry, row: HistoryRow): RevertFlow | null {
        const flow = this.flow();
        return flow !== null && flow.entryId === entry.deckRevisionId && flow.unitId === row.unit.unitId ? flow : null;
    }

    protected revertMarker(entry: HistoryEntry, row: HistoryRow): string {
        return `${entry.deckRevisionId}|${row.unit.unitId}`;
    }

    protected askText(flow: RevertFlow): string {
        return UPDATE_TEXT.revertAsk[flow.change](flow.title);
    }

    protected withText(flow: RevertFlow): string | null {
        return flow.group ? UPDATE_TEXT.revertWith(flow.with.map(unitId => `«${this.nameOf(flow.entryId, unitId)}»`).join(', ')) : null;
    }

    /** Anything else of the page that is busy keeps the buttons of other rows from starting a second return. */
    protected readonly returning = computed(() => this.flow()?.busy === true);

    // -- returning ----------------------------------------------------------------------------

    ask(entry: HistoryEntry, row: HistoryRow): void {
        if (this.returning() || row.unit.change === 'REORDERED') return;
        this.notice.set(null);
        this.flow.set({
            entryId: entry.deckRevisionId, unitId: row.unit.unitId, title: row.view.title, change: row.unit.change,
            commandId: newRevertCommandId(), with: [], group: false, busy: false, problem: null, unknown: false
        });
        this.focusFlow();
    }

    cancel(): void {
        const flow = this.flow();
        if (flow === null || flow.busy) return;
        this.flow.set(null);
        afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>(`[data-revert="${flow.entryId}|${flow.unitId}"]`)?.focus(), { injector: this.injector });
    }

    /** Sends the command of the open confirmation: the first step, the second with the units that come along, or the same command again. */
    confirm(): void {
        const flow = this.flow();
        if (flow === null || flow.busy) return;
        this.flow.set({ ...flow, busy: true, problem: null, unknown: false });
        this.revertRequest?.unsubscribe();
        this.revertRequest = this.api.revert(this.deckId(), flow.entryId, flow.unitId, { commandId: flow.commandId, with: flow.with }).subscribe({
            next: answer => this.reverted(flow, answer.units.length - 1),
            error: error => this.failed(flow, revertFailureOf(error))
        });
    }

    private reverted(flow: RevertFlow, more: number): void {
        this.flow.set(null);
        const message = UPDATE_TEXT.revertDone(flow.title, more);
        this.toasts.echo(message);
        this.liveMessage.set(message);
        this.refresh();
        afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>('h1')?.focus(), { injector: this.injector });
    }

    private failed(flow: RevertFlow, failure: RevertFailure): void {
        switch (failure.kind) {
            case 'unit-needed': {
                const known = new Set(flow.with);
                const units = [...flow.with, ...failure.units.filter(unitId => !known.has(unitId) && unitId !== flow.unitId)];
                // Nothing new to add, or more than one command can carry: the group cannot be asked for.
                if (units.length === flow.with.length || units.length > REVERT_MAX_WITH) return this.stay(flow, UPDATE_TEXT.revertRefused, false);
                this.flow.set({ ...flow, with: units, group: true, busy: false, problem: null, unknown: false, commandId: newRevertCommandId() });
                this.focusFlow();
                return;
            }
            case 'still-used': return this.stay(flow, UPDATE_TEXT.revertStillUsed(failure.dependency === null ? null : this.knownTitle(flow.entryId, failure.dependency)), false);
            case 'not-returnable': return this.stay(flow, UPDATE_TEXT.revertNotReturnable, false);
            case 'refused':
            case 'other': return this.stay(flow, UPDATE_TEXT.revertRefused, false);
            case 'rate-limited': return this.stay(flow, UPDATE_TEXT.revertRateLimited, false);
            case 'unknown': return this.stay(flow, UPDATE_TEXT.revertUnknown, true);
            case 'stale': return this.close(failure.unitId === null ? UPDATE_TEXT.revertStaleCopy : UPDATE_TEXT.revertStaleUnit);
            case 'detached': return this.close(UPDATE_TEXT.revertDetached);
            case 'preparing': return this.close(UPDATE_TEXT.revertPreparing);
            case 'not-found': return this.close(UPDATE_TEXT.revertGone);
        }
    }

    /** The confirmation stays open and says why; the same command can be sent again. */
    private stay(flow: RevertFlow, problem: string, unknown: boolean): void {
        this.flow.set({ ...flow, busy: false, problem, unknown });
    }

    /** The return cannot go on as it was asked: say why above the list and read what is true now. */
    private close(message: string): void {
        this.flow.set(null);
        this.notice.set(message);
        this.liveMessage.set(message);
        this.refresh();
    }

    private refresh(): void {
        this.loadDeck();
        this.loadHistory();
    }

    /** The title the entry lists for the unit, or `null` when it lists none (a unit past the cut of the entry, a taken-down revision). */
    private knownTitle(entryId: string, unitId: string): string | null {
        const title = this.entries().find(entry => entry.deckRevisionId === entryId)?.units.find(candidate => candidate.unitId === unitId)?.title?.trim() ?? '';
        return title.length > 0 ? title : null;
    }

    /** The name of a unit for a sentence: its title, else the word for its kind. */
    private nameOf(entryId: string, unitId: string): string {
        const known = this.knownTitle(entryId, unitId);
        if (known !== null) return known;
        const kind = splitUnitId(unitId)?.kind;
        return kind === undefined ? UPDATE_TEXT.untitled.material : UPDATE_TEXT.untitled[viewKindOf(kind)];
    }

    private focusFlow(): void {
        afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>('[data-revert-ask]')?.focus(), { injector: this.injector });
    }
}

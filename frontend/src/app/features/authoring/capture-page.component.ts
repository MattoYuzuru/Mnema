import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, afterRenderEffect, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AutoLoadComponent } from '../../shared/auto-load.component';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';

import { NativeDocument } from '../../content/native-document';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { MicButtonComponent } from '../speech/mic-button.component';
import { MAX_NOTES, NOTES_QUERY_PARAM, noteExcerpt, serializeNoteIds } from '../generation/note-sources';
import { AuthoringApiService } from './authoring-api.service';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from './capabilities-api.service';
import { CaptureNote, newCommandId } from './authoring.models';

@Component({
    selector: 'app-capture-page',
    imports: [ReactiveFormsModule, RouterLink, MicButtonComponent, AutoLoadComponent],
    templateUrl: './capture-page.component.html',
    styleUrls: ['./authoring-page.css', './capture-page.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { '(keydown.escape)': 'clearSelectionFromKeyboard($event)', '(focusin)': 'revealSelectionFocus($event)' }
})
export class CapturePageComponent {
    readonly form = new FormGroup({ text: new FormControl('', { nonNullable: true, validators: [captureTextValidator] }) });
    readonly deck = signal<OwnDeck | null>(null);
    readonly notes = signal<readonly CaptureNote[]>([]);
    readonly total = signal(0);
    readonly totalLabel = computed(() => {
        const count = this.total();
        const ending = count % 100;
        const word = ending >= 11 && ending <= 14 ? 'заметок' : count % 10 === 1 ? 'заметка'
            : count % 10 >= 2 && count % 10 <= 4 ? 'заметки' : 'заметок';
        return `${count} ${word}`;
    });
    readonly loading = signal(true);
    readonly nextCursor = signal<string | null>(null);
    readonly loadingMore = signal(false);
    readonly moreError = signal(false);
    private readonly selectAllBox = viewChild<ElementRef<HTMLInputElement>>('selectAll');
    private readonly selectionBar = viewChild<ElementRef<HTMLElement>>('selectionBar');
    private readonly hostElement = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    readonly busy = signal(false);
    readonly error = signal<string | null>(null);
    readonly recovery = signal<'reload' | 'retry' | null>(null);
    /** The server offers AI generation (`aiGeneration`); false while unknown and when the read fails (fail closed). */
    readonly generationAvailable = signal(false);
    /** The server offers speech-to-text (`speechToText`): the microphone sits next to the note field. */
    readonly speechAvailable = signal(false);
    private readonly noteField = viewChild<ElementRef<HTMLTextAreaElement>>('noteField');
    protected readonly noteTarget = (): HTMLTextAreaElement | null => this.noteField()?.nativeElement ?? null;
    /** Ids of the picked notes (AI-08, #290). Only notes still in the list count, see {@link selectedNotes}. */
    readonly selected = signal<ReadonlySet<string>>(new Set());
    /** Why a pick was refused or cut short: said in words, never silently. */
    readonly selectionNote = signal<string | null>(null);
    readonly maxNotes = MAX_NOTES;
    /** The picked notes in list order. */
    readonly selectedNotes = computed(() => this.notes().filter(note => this.selected().has(note.noteId)));
    readonly selectedCount = computed(() => this.selectedNotes().length);
    /** State of «Выбрать все загруженные» over the notes that are loaded now. */
    readonly allState = computed<'checked' | 'mixed' | 'unchecked'>(() => {
        const notes = this.notes();
        const picked = this.selectedCount();
        if (notes.length === 0 || picked === 0) return 'unchecked';
        // With more than 20 loaded, 20 is as many as can be picked: that is «all», and the next press clears.
        return picked === Math.min(notes.length, MAX_NOTES) ? 'checked' : 'mixed';
    });
    /** The limit sentence: standing while the maximum is picked, and the explanation of a pick that was cut short. */
    readonly limitText = computed(() => this.selectionNote()
        ?? (this.selectedCount() >= MAX_NOTES ? `Выбрано максимум: за один раз можно не больше ${MAX_NOTES} заметок.` : ''));

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly decks = inject(OwnDecksApiService);
    private readonly api = inject(AuthoringApiService);
    private readonly capabilities = inject(CapabilitiesApiService);
    private readonly destroyRef = inject(DestroyRef);
    private pendingCapture: { readonly commandId: string; readonly text: string } | null = null;
    private pendingConversion: {
        readonly commandId: string; readonly note: CaptureNote; readonly document: NativeDocument;
    } | null = null;

    constructor() {
        this.capabilities.read().pipe(catchError(() => of(CAPABILITIES_UNAVAILABLE)), takeUntilDestroyed(this.destroyRef))
            .subscribe(result => { this.generationAvailable.set(result.aiGeneration.available); this.speechAvailable.set(result.speechToText.available); });
        this.load();
        // The rail can grow when labels wrap or text is enlarged. Reserve its measured height on the viewport,
        // then reveal the whole checkbox label when Space inserts it beneath an already focused selection.
        afterRenderEffect(onCleanup => {
            const bar = this.selectionBar()?.nativeElement;
            if (bar === undefined) return;
            const root = bar.ownerDocument.documentElement;
            const previous = root.style.getPropertyValue('--mn-bulk-bar-height');
            let active = true;
            const apply = (): void => {
                if (!active) return;
                root.style.setProperty('--mn-bulk-bar-height', `${bar.offsetHeight + 8}px`);
                this.revealSelectionLabel(bar.ownerDocument.activeElement);
            };
            apply();
            const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply);
            observer?.observe(bar);
            onCleanup(() => {
                active = false;
                observer?.disconnect();
                if (previous) root.style.setProperty('--mn-bulk-bar-height', previous);
                else root.style.removeProperty('--mn-bulk-bar-height');
            });
        });
    }

    load(): void {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        if (deckId === null) { this.error.set('Некорректный адрес колоды.'); this.loading.set(false); return; }
        this.pendingCapture = null;
        this.pendingConversion = null;
        this.recovery.set(null);
        this.selected.set(new Set());
        this.selectionNote.set(null);
        this.loading.set(true);
        this.error.set(null);
        this.moreError.set(false);
        forkJoin({ deck: this.decks.detail(deckId), captures: this.api.listDeckCaptures(deckId) })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    this.deck.set(result.deck);
                    this.notes.set(result.captures.items);
                    this.total.set(result.captures.total);
                    this.nextCursor.set(result.captures.nextCursor);
                    this.loading.set(false);
                },
                error: () => { this.error.set('Не удалось загрузить заметки.'); this.loading.set(false); }
            });
    }

    loadMore(): void {
        const deck = this.deck();
        const cursor = this.nextCursor();
        if (deck === null || cursor === null || this.loading() || this.loadingMore()) return;
        this.loadingMore.set(true);
        this.moreError.set(false);
        this.api.listDeckCaptures(deck.deckId, cursor).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.notes.update(notes => [...notes, ...result.items.filter(note =>
                    !notes.some(existing => existing.noteId === note.noteId))]);
                this.total.set(result.total);
                this.nextCursor.set(result.nextCursor);
                this.loadingMore.set(false);
            },
            error: () => { this.moreError.set(true); this.loadingMore.set(false); }
        });
    }

    capture(retry = false): void {
        const deck = this.deck();
        const noteText = this.form.controls.text.value.trim();
        if (deck === null || this.busy() || (!retry && (noteText.length === 0 || this.pendingCapture !== null))) return;
        const pending = retry && this.pendingCapture !== null
            ? this.pendingCapture : { commandId: newCommandId(), text: noteText };
        this.pendingCapture = pending;
        this.busy.set(true);
        this.error.set(null);
        this.recovery.set(null);
        this.api.createCapture(deck.deckId, 'manual', pending.text, pending.commandId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    this.pendingCapture = null;
                    const created = result.acknowledgement.capture;
                    const alreadyVisible = this.notes().some(note => note.noteId === created.noteId);
                    this.notes.update(notes => [created, ...notes.filter(note => note.noteId !== created.noteId)]);
                    if (!alreadyVisible) this.total.update(total => total + 1);
                    this.form.reset();
                    this.busy.set(false);
                },
                error: failure => {
                    const uncertain = isUncertain(failure);
                    if (!uncertain) this.pendingCapture = null;
                    this.recovery.set(uncertain ? 'retry' : 'reload');
                    this.error.set(uncertain
                        ? 'Не удалось проверить, сохранилась ли заметка. Попробуйте ещё раз.'
                        : 'Заметка не сохранена. Обновите список и попробуйте снова.');
                    this.busy.set(false);
                }
            });
    }

    convert(note: CaptureNote, retry = false): void {
        const deck = this.deck();
        if (deck === null || note.conversion !== null || this.busy()
            || (!retry && this.pendingConversion !== null)) return;
        const pending = retry && this.pendingConversion !== null ? this.pendingConversion : {
            commandId: newCommandId(), note, document: documentFromText(note.text)
        };
        this.pendingConversion = pending;
        this.busy.set(true);
        this.error.set(null);
        this.recovery.set(null);
        this.api.convertCapture(pending.note, deck.rowVersion, deck.revisionId, pending.document, pending.commandId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    this.pendingConversion = null;
                    const memberKey = result.publication.changes[0]?.memberKey;
                    const ordinal = result.publication.changes[0]?.ordinal;
                    if (memberKey === undefined || ordinal === null || ordinal === undefined) {
                        this.error.set('Не удалось открыть созданный материал. Обновите список и попробуйте снова.');
                        this.recovery.set('reload');
                        this.busy.set(false);
                        return;
                    }
                    void this.router.navigate(['/decks', deck.deckId, 'materials', memberKey, 'edit'], {
                        queryParams: { ordinal }
                    });
                },
                error: failure => {
                    const uncertain = isUncertain(failure);
                    if (!uncertain) this.pendingConversion = null;
                    this.recovery.set(uncertain ? 'retry' : 'reload');
                    this.error.set(uncertain
                        ? 'Не удалось проверить результат. Попробуйте ещё раз.'
                        : 'Колода или заметка изменилась. Обновите страницу и попробуйте снова.');
                    this.busy.set(false);
                }
            });
    }

    // --- Picking notes for «Создать материалы с ИИ» ---

    protected excerptOf(note: CaptureNote): string { return noteExcerpt(note.text); }

    isSelected(note: CaptureNote): boolean { return this.selected().has(note.noteId); }

    /** Picks or drops one note. A pick beyond the limit is refused with the reason, and the box goes back to unchecked. */
    toggleNote(note: CaptureNote, event: Event): void {
        const box = event.target as HTMLInputElement;
        if (!box.checked) {
            this.selected.update(held => { const next = new Set(held); next.delete(note.noteId); return next; });
            this.selectionNote.set(null);
            return;
        }
        if (this.selectedCount() >= MAX_NOTES) {
            box.checked = false;
            this.selectionNote.set(`За один раз можно выбрать не больше ${MAX_NOTES} заметок. Создайте материалы по этим, а потом вернитесь за остальными.`);
            return;
        }
        this.selected.update(held => new Set(held).add(note.noteId));
        this.selectionNote.set(null);
    }

    /** The header checkbox: picks the loaded notes (the first {@link MAX_NOTES} of them), or clears them when all are picked. */
    toggleLoaded(): void {
        const notes = this.notes();
        if (this.allState() === 'checked') { this.clearSelection(); return; }
        const picked = notes.slice(0, MAX_NOTES);
        this.selected.set(new Set(picked.map(note => note.noteId)));
        this.selectionNote.set(notes.length > MAX_NOTES
            ? `Выбрали первые ${MAX_NOTES} из ${notes.length} загруженных: за один раз можно не больше ${MAX_NOTES} заметок.` : null);
    }

    /** Clears the picks. The bar (and its focused button) goes away, so focus moves to «Выбрать все загруженные». */
    clearSelection(): void {
        const hadBar = this.selectedCount() > 0;
        this.selected.set(new Set());
        this.selectionNote.set(null);
        if (hadBar) afterNextRender(() => this.selectAllBox()?.nativeElement.focus(), { injector: this.injector });
    }

    /** Keep the complete touch label visible, not just the smaller checkbox the browser scrolls to on Tab. */
    protected revealSelectionFocus(event: Event): void {
        const target = event.target;
        if (!(target instanceof HTMLElement) || target.closest('.select-all, .note-check') === null) return;
        afterNextRender(() => this.revealSelectionLabel(target), { injector: this.injector });
    }

    private revealSelectionLabel(target: Element | null): void {
        if (this.destroyRef.destroyed || target === null || !this.hostElement.nativeElement.contains(target)) return;
        target.closest('.select-all, .note-check')?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
    }

    /** Escape clears the picks only from the list or the bar, never while typing in a field (the note field, a toggletip). */
    clearSelectionFromKeyboard(event: Event): void {
        const target = event.target as HTMLElement | null;
        if (this.selectedCount() === 0 || event.defaultPrevented || target === null) return;
        if (target.closest('textarea, input:not([type=checkbox]), select, [contenteditable]') !== null) return;
        if (target.closest('.capture-list, .selection-bar, .selection-head') === null) return;
        this.clearSelection();
    }

    /** Takes the picked notes to the composer. It reads them again there, so a note edited in the meantime is pinned as it is now. */
    createMaterials(): void {
        const deck = this.deck();
        const notes = this.selectedNotes();
        if (deck === null || !this.generationAvailable() || notes.length === 0 || notes.length > MAX_NOTES) return;
        void this.router.navigate(['/decks', deck.deckId, 'materials', 'new'], {
            queryParams: { [NOTES_QUERY_PARAM]: serializeNoteIds(notes.map(note => note.noteId)) }
        });
    }

    retry(): void {
        if (this.pendingConversion !== null) this.convert(this.pendingConversion.note, true);
        else if (this.pendingCapture !== null) this.capture(true);
        else this.load();
    }

    deleteNote(note: CaptureNote): void {
        if (this.busy() || this.recovery() === 'retry') return;
        this.busy.set(true);
        this.error.set(null);
        this.api.deleteCapture(note).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: () => {
                this.notes.update(notes => notes.filter(item => item.noteId !== note.noteId));
                this.total.update(total => Math.max(0, total - 1));
                this.busy.set(false);
            },
            error: () => {
                this.error.set('Не удалось убрать заметку. Обновите список и попробуйте снова.');
                this.recovery.set('reload');
                this.busy.set(false);
            }
        });
    }
}

export function documentFromText(text: string): NativeDocument {
    return {
        formatVersion: 1,
        root: { id: crypto.randomUUID(), type: 'doc', version: 1, attrs: {}, content: [{
            id: crypto.randomUUID(), type: 'paragraph', version: 1, attrs: {}, content: text.length === 0 ? [] : [{
                id: crypto.randomUUID(), type: 'text', version: 1, attrs: { text, marks: [] }, content: []
            }]
        }] }
    };
}

function captureTextValidator(control: AbstractControl<string>): ValidationErrors | null {
    const text = control.value.trim();
    if (text.length === 0) return { required: true };
    return new TextEncoder().encode(text).byteLength <= 32_768 ? null : { maxBytes: true };
}

function isUncertain(error: unknown): boolean {
    return !(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500;
}

import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { NativeDocument } from '../../content/native-document';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringApiService } from './authoring-api.service';
import { CaptureNote, newCommandId } from './authoring.models';

@Component({
    selector: 'app-capture-page',
    imports: [ReactiveFormsModule, RouterLink],
    templateUrl: './capture-page.component.html',
    styleUrl: './authoring-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
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
    readonly loadSentinel = viewChild<ElementRef<HTMLElement>>('loadSentinel');
    readonly busy = signal(false);
    readonly error = signal<string | null>(null);
    readonly recovery = signal<'reload' | 'retry' | null>(null);

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly decks = inject(OwnDecksApiService);
    private readonly api = inject(AuthoringApiService);
    private readonly destroyRef = inject(DestroyRef);
    private pendingCapture: { readonly commandId: string; readonly text: string } | null = null;
    private pendingConversion: {
        readonly commandId: string; readonly note: CaptureNote; readonly document: NativeDocument;
    } | null = null;

    constructor() {
        this.load();
        effect(onCleanup => {
            const sentinel = this.loadSentinel()?.nativeElement;
            if (!sentinel || !this.nextCursor() || this.loading() || this.loadingMore() || this.moreError()) return;
            const observer = new IntersectionObserver(entries => {
                if (entries.some(entry => entry.isIntersecting)) this.loadMore();
            }, { rootMargin: '0px 0px 800px 0px' });
            observer.observe(sentinel);
            onCleanup(() => observer.disconnect());
        });
    }

    load(): void {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        if (deckId === null) { this.error.set('Некорректный адрес колоды.'); this.loading.set(false); return; }
        this.pendingCapture = null;
        this.pendingConversion = null;
        this.recovery.set(null);
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

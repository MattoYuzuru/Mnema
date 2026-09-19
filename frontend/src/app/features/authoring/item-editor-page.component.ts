import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, HostListener, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, forkJoin, of } from 'rxjs';

import { NativeDocument } from '../../content/native-document';
import { createEmptyNativeDocument } from '../../content/editing/native-editor-adapter';
import { NativeEditorComponent } from '../../content/editing/native-editor.component';
import { NativeStructuralEdit, planNativeStructuralEdits } from '../../content/editing/native-structural-edits';
import { NativeDocumentRendererComponent } from '../../content/rendering/native-document-renderer.component';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { AuthoringApiService } from './authoring-api.service';
import { DraftDetail, DraftSummary, ItemDetail, ItemWriteResult, newCommandId } from './authoring.models';
import { ItemApiService } from './item-api.service';

type EditorPhase = 'loading' | 'ready' | 'saving-draft' | 'publishing' | 'conflict' | 'rejected' | 'error';

@Component({
    selector: 'app-item-editor-page',
    imports: [RouterLink, NativeEditorComponent, NativeDocumentRendererComponent],
    templateUrl: './item-editor-page.component.html',
    styleUrl: './authoring-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ItemEditorPageComponent {
    readonly deck = signal<OwnDeck | null>(null);
    readonly item = signal<ItemDetail | null>(null);
    readonly draft = signal<DraftDetail | null>(null);
    readonly document = signal<NativeDocument | null>(null);
    readonly phase = signal<EditorPhase>('loading');
    readonly dirty = signal(false);
    readonly restored = signal(false);
    readonly message = signal<string | null>(null);
    readonly conflict = signal<'draft' | 'publication' | null>(null);

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly decks = inject(OwnDecksApiService);
    private readonly items = inject(ItemApiService);
    private readonly authoring = inject(AuthoringApiService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly changes = new Subject<NativeDocument>();
    private queuedSave = false;
    private pendingDraftCreation: {
        readonly deck: OwnDeck; readonly item: ItemDetail | null;
        readonly commandId: string; readonly document: NativeDocument;
    } | null = null;
    private pendingDraft: { readonly commandId: string; readonly document: NativeDocument } | null = null;
    private pendingPublication: {
        readonly commandId: string; readonly document: NativeDocument; readonly edits: readonly NativeStructuralEdit[];
    } | null = null;
    private pendingNavigation: {
        readonly deckId: string; readonly memberKey: string; readonly ordinal: number;
        readonly draftId: string; readonly draftVersion: string;
    } | null = null;
    private expectedOrdinal: number | null = null;

    constructor() {
        this.changes.pipe(debounceTime(1_000), takeUntilDestroyed()).subscribe(() => this.saveDraft());
        this.load();
    }

    load(): void {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        const routeMember = this.route.snapshot.paramMap.get('memberKey');
        this.expectedOrdinal = parseOrdinal(this.route.snapshot.queryParamMap.get('ordinal'));
        if (deckId === null) { this.fail('Некорректный адрес колоды.'); return; }
        const memberKey = routeMember === 'new' ? null : routeMember;
        if (memberKey !== null && this.expectedOrdinal === null) {
            this.fail('Откройте редактор из актуального списка материалов, чтобы подтвердить его позицию.');
            return;
        }
        this.phase.set('loading');
        this.message.set(null);
        this.document.set(null);
        this.draft.set(null);
        this.item.set(null);
        this.restored.set(false);
        this.conflict.set(null);
        this.dirty.set(false);
        this.pendingDraft = null;
        this.pendingDraftCreation = null;
        this.pendingPublication = null;
        this.pendingNavigation = null;
        this.queuedSave = false;
        const itemRequest = memberKey === null ? of(null) : this.items.read(deckId, memberKey);
        forkJoin({ deck: this.decks.detail(deckId), item: itemRequest, drafts: this.authoring.listAllDrafts() })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    const item = result.item;
                    this.deck.set(result.deck);
                    this.item.set(item);
                    const matching = newestMatchingDraft(result.drafts.items, result.deck.deckId, item?.memberKey ?? null,
                        item?.itemRevisionId ?? null);
                    if (matching === null) this.createDraft(result.deck, item);
                    else this.openDraft(matching);
                },
                error: () => this.fail('Не удалось загрузить материал и серверные черновики.')
            });
    }

    changeDocument(document: NativeDocument): void {
        this.document.set(document);
        this.dirty.set(true);
        this.message.set(null);
        if (this.phase() === 'error' || this.phase() === 'rejected') this.phase.set('ready');
        this.changes.next(document);
    }

    saveDraft(retry = false): void {
        const draft = this.draft();
        const document = this.document();
        if (draft === null || document === null || (!this.dirty() && !retry)) return;
        if (this.phase() === 'conflict') return;
        if (this.phase() === 'saving-draft' || this.phase() === 'publishing') { this.queuedSave = true; return; }
        const pending = retry && this.pendingDraft !== null
            ? this.pendingDraft : { commandId: newCommandId(), document };
        this.pendingDraft = pending;
        this.phase.set('saving-draft');
        this.authoring.updateDraft(draft.draftId, draft.rowVersion, pending.document, pending.commandId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    if (result.replayed) {
                        this.reconcileReplayedDraft(draft.draftId, pending);
                        return;
                    }
                    this.draft.set(result.acknowledgement.draft);
                    this.pendingDraft = null;
                    const isCurrent = this.document() === pending.document;
                    this.dirty.set(!isCurrent);
                    this.phase.set('ready');
                    this.message.set(isCurrent ? 'Черновик подтверждён сервером.' : null);
                    if (!isCurrent || this.queuedSave) {
                        this.queuedSave = false;
                        queueMicrotask(() => this.saveDraft());
                    }
                },
                error: error => {
                    const stale = error instanceof HttpErrorResponse && error.status === 412;
                    const uncertain = isUncertain(error);
                    if (!uncertain) this.pendingDraft = null;
                    this.conflict.set(stale ? 'draft' : null);
                    this.phase.set(stale ? 'conflict' : uncertain ? 'error' : 'rejected');
                    this.message.set(stale
                        ? 'Черновик изменён в другой вкладке. Ваш ввод остался в этой вкладке.'
                        : uncertain
                            ? 'Сервер не подтвердил черновик. Безопасно повторите ту же команду.'
                            : 'Черновик отклонён сервером. Исправьте материал и сохраните его новой командой.');
                }
            });
    }

    publish(retry = false): void {
        const deck = this.deck();
        const draft = this.draft();
        const document = this.document();
        const item = this.item();
        if (deck === null || draft === null || document === null || this.dirty()
            || (this.phase() !== 'ready' && !retry)) return;
        if (item !== null && this.expectedOrdinal === null) {
            this.fail('Откройте редактор из актуального списка материалов, чтобы подтвердить позицию.');
            return;
        }
        let edits: readonly NativeStructuralEdit[] = [];
        if (!retry && item !== null) {
            try {
                edits = planNativeStructuralEdits(item.document, document);
            } catch {
                this.phase.set('rejected');
                this.message.set('Структурная правка слишком велика для одной публикации. Сократите изменение и повторите.');
                return;
            }
        }
        const pending = retry && this.pendingPublication !== null
            ? this.pendingPublication : { commandId: newCommandId(), document, edits };
        this.pendingPublication = pending;
        this.phase.set('publishing');
        const publication = item === null
            ? this.items.create(deck.deckId, deck.rowVersion, deck.revisionId, pending.document, pending.commandId)
            : this.items.save(deck.deckId, item.memberKey, item.deckVersion, item.deckRevisionId,
                item.itemRevisionId, this.expectedOrdinal!, pending.document, pending.commandId, pending.edits);
        publication.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                const memberKey = result.acknowledgement.changes[0]?.memberKey;
                const ordinal = result.acknowledgement.changes[0]?.ordinal;
                if (memberKey === undefined || ordinal === null || ordinal === undefined) {
                    this.fail('Сервер вернул неполное подтверждение публикации.');
                    return;
                }
                if (result.replayed) {
                    this.reconcileReplayedPublication(result, deck, draft, memberKey, ordinal);
                } else {
                    this.finishPublication(deck, draft, memberKey, ordinal);
                }
            },
            error: error => {
                const stale = error instanceof HttpErrorResponse && error.status === 412;
                const uncertain = isUncertain(error);
                if (!uncertain) this.pendingPublication = null;
                this.conflict.set(stale ? 'publication' : null);
                this.phase.set(stale ? 'conflict' : uncertain ? 'error' : 'rejected');
                this.message.set(stale
                    ? 'Колода или материал изменились. Ваш серверный черновик сохранён; загрузите свежую основу.'
                    : uncertain
                        ? 'Публикация не подтверждена. Безопасно повторите ту же команду.'
                        : 'Публикация отклонена сервером. Исправьте материал и отправьте новую команду.');
            }
        });
    }

    retry(): void {
        if (this.pendingNavigation !== null) this.cleanupPublishedDraft();
        else if (this.pendingPublication !== null) this.publish(true);
        else if (this.pendingDraft !== null) this.saveDraft(true);
        else if (this.pendingDraftCreation !== null) {
            this.createDraft(this.pendingDraftCreation.deck, this.pendingDraftCreation.item, true);
        }
        else this.load();
    }

    reload(): void {
        if (!this.dirty() || window.confirm('Заменить неподтверждённый ввод серверной версией?')) this.load();
    }

    resolveDraftConflict(keepLocal: boolean): void {
        const draft = this.draft();
        const local = this.document();
        if (draft === null || local === null) { this.reload(); return; }
        this.phase.set('loading');
        this.authoring.readDraft(draft.draftId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: current => {
                this.draft.set(current);
                this.pendingDraft = null;
                this.conflict.set(null);
                if (!keepLocal) this.document.set(current.document);
                this.dirty.set(keepLocal);
                this.phase.set('ready');
                this.message.set(keepLocal
                    ? 'Свежая основа получена. Ваш ввод готов к явному сохранению поверх неё.'
                    : 'Загружена подтверждённая серверная версия.');
            },
            error: () => this.fail('Не удалось получить свежую версию черновика.')
        });
    }

    confirmLeave(): boolean {
        return !this.dirty() || window.confirm('Черновик ещё не подтверждён сервером. Покинуть страницу?');
    }

    @HostListener('window:beforeunload', ['$event'])
    protectUnacknowledgedInput(event: BeforeUnloadEvent): void {
        if (this.dirty()) event.preventDefault();
    }

    private openDraft(summary: DraftSummary): void {
        this.authoring.readDraft(summary.draftId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: draft => {
                this.draft.set(draft);
                this.document.set(draft.document);
                this.dirty.set(false);
                this.restored.set(true);
                this.phase.set('ready');
            },
            error: () => this.fail('Не удалось открыть серверный черновик.')
        });
    }

    private createDraft(deck: OwnDeck, item: ItemDetail | null, retry = false): void {
        const pending = retry && this.pendingDraftCreation !== null ? this.pendingDraftCreation : {
            deck, item, commandId: newCommandId(), document: item?.document ?? createEmptyNativeDocument()
        };
        this.pendingDraftCreation = pending;
        this.phase.set('loading');
        this.authoring.createDraft(pending.deck.deckId, pending.document, pending.item?.memberKey ?? null,
            pending.item?.itemRevisionId ?? null, pending.commandId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: result => {
                    this.pendingDraftCreation = null;
                    if (result.replayed) {
                        this.openDraft(result.acknowledgement.draft);
                        return;
                    }
                    this.draft.set(result.acknowledgement.draft);
                    this.document.set(result.acknowledgement.draft.document);
                    this.dirty.set(false);
                    this.phase.set('ready');
                },
                error: failure => {
                    const uncertain = isUncertain(failure);
                    if (!uncertain) this.pendingDraftCreation = null;
                    this.fail(uncertain
                        ? 'Создание черновика не подтверждено. Безопасно повторите ту же команду.'
                        : 'Не удалось создать серверный черновик.');
                }
            });
    }

    private reconcileReplayedDraft(draftId: string,
                                    pending: { readonly commandId: string; readonly document: NativeDocument }): void {
        this.authoring.readDraft(draftId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: current => {
                this.draft.set(current);
                this.pendingDraft = null;
                if (!sameJson(current.document, pending.document)) {
                    this.conflict.set('draft');
                    this.phase.set('conflict');
                    this.message.set('Серверный черновик уже продолжили в другом месте. Ваш ввод не потерян; выберите свежую версию.');
                    return;
                }
                const isCurrent = this.document() === pending.document;
                this.dirty.set(!isCurrent);
                this.phase.set('ready');
                this.message.set(isCurrent ? 'Повтор команды подтверждён и сверен с сервером.' : null);
                if (!isCurrent || this.queuedSave) {
                    this.queuedSave = false;
                    queueMicrotask(() => this.saveDraft());
                }
            },
            error: () => {
                this.phase.set('error');
                this.message.set('Команда подтверждена повтором, но свежую версию черновика получить не удалось.');
            }
        });
    }

    private reconcileReplayedPublication(result: ItemWriteResult, deck: OwnDeck, draft: DraftDetail,
                                          memberKey: string, ordinal: number): void {
        this.items.read(deck.deckId, memberKey).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: current => {
                const change = result.acknowledgement.changes[0];
                if (change?.itemRevisionId !== current.itemRevisionId
                    || result.acknowledgement.deckRevisionId !== current.deckRevisionId) {
                    this.pendingPublication = null;
                    this.conflict.set('publication');
                    this.phase.set('conflict');
                    this.message.set('Публикация подтверждена, но колода уже изменилась. Черновик сохранён; вернитесь в актуальный Browse.');
                    return;
                }
                this.finishPublication(deck, draft, memberKey, ordinal);
            },
            error: () => {
                this.phase.set('error');
                this.message.set('Повтор публикации подтверждён, но актуальную серверную версию проверить не удалось.');
            }
        });
    }

    private finishPublication(deck: OwnDeck, draft: DraftDetail, memberKey: string, ordinal: number): void {
        this.pendingPublication = null;
        this.pendingNavigation = {
            deckId: deck.deckId, memberKey, ordinal, draftId: draft.draftId, draftVersion: draft.rowVersion
        };
        this.cleanupPublishedDraft();
    }

    private cleanupPublishedDraft(): void {
        const navigation = this.pendingNavigation;
        if (navigation === null) return;
        this.phase.set('publishing');
        this.authoring.deleteDraft(navigation.draftId, navigation.draftVersion)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: () => this.finishNavigation(navigation),
                error: error => {
                    if (error instanceof HttpErrorResponse && error.status === 404) {
                        this.finishNavigation(navigation);
                        return;
                    }
                    this.phase.set('error');
                    this.message.set('Материал опубликован, но удаление исходного черновика не подтверждено. Повторите очистку.');
                }
            });
    }

    private finishNavigation(navigation: { readonly deckId: string; readonly memberKey: string; readonly ordinal: number }): void {
        this.pendingNavigation = null;
        void this.router.navigate(['/decks', navigation.deckId, 'materials', navigation.memberKey], {
            queryParams: { ordinal: navigation.ordinal }
        });
    }

    private fail(message: string): void { this.message.set(message); this.phase.set('error'); }
}

export function canLeaveItemEditor(component: ItemEditorPageComponent): boolean {
    return component.confirmLeave();
}

function sameJson(left: unknown, right: unknown): boolean {
    const normalize = (value: unknown): unknown => {
        if (Array.isArray(value)) return value.map(normalize);
        if (value !== null && typeof value === 'object') {
            return Object.fromEntries(Object.entries(value as Record<string, unknown>)
                .sort(([leftKey], [rightKey]) => leftKey.localeCompare(rightKey))
                .map(([key, entry]) => [key, normalize(entry)]));
        }
        return value;
    };
    return JSON.stringify(normalize(left)) === JSON.stringify(normalize(right));
}

function parseOrdinal(value: string | null): number | null {
    if (value === null || !/^(0|[1-9][0-9]{0,4})$/.test(value)) return null;
    const ordinal = Number(value);
    return ordinal < 100_000 ? ordinal : null;
}

function newestMatchingDraft(drafts: readonly DraftSummary[], deckId: string, memberKey: string | null,
                             baseRevisionId: string | null): DraftSummary | null {
    return drafts.filter(draft => draft.deckId === deckId && draft.memberKey === memberKey
        && draft.baseRevisionId === baseRevisionId).sort((left, right) => right.acknowledgedAt.localeCompare(left.acknowledgedAt))[0] ?? null;
}

function isUncertain(error: unknown): boolean {
    return !(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500;
}

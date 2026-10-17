import { TourAnchorDirective } from '../../core/onboarding/tour-anchor.directive';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription, forkJoin } from 'rxjs';

import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { AskMnemaComponent } from '../generation/ask-mnema.component';
import { IntentContext } from '../generation/generation-intent';
import { OwnDeck } from '../own-decks/own-deck.models';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from './capabilities-api.service';
import { ReadingFrameComponent } from '../../shared/reading-frame.component';
import { MaterialNavigation, MaterialNavigationApiService } from './material-navigation-api.service';
import { ItemApiService } from './item-api.service';
import { ItemDetail, isTakenDown, newCommandId } from './authoring.models';

@Component({
    selector: 'app-browse-page',
    imports: [RouterLink, TourAnchorDirective, NativeMediaSurfaceComponent, HoldToDeleteButtonComponent, AskMnemaComponent, ReadingFrameComponent],
    templateUrl: './browse-page.component.html',
    styleUrl: './authoring-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class BrowsePageComponent {
    readonly deck = signal<OwnDeck | null>(null);
    readonly item = signal<ItemDetail | null>(null);
    readonly selectedOrdinal = signal<number | null>(null);
    readonly loading = signal(true);
    readonly navigation = signal<MaterialNavigation | null>(null);
    readonly navigationFailure = signal(false);
    protected readonly previousLink = computed(() => this.neighbourLink(this.navigation()?.previousMemberKey));
    protected readonly nextLink = computed(() => this.neighbourLink(this.navigation()?.nextMemberKey));
    readonly failure = signal(false);
    /** The material was taken down after a complaint (`410`, Share/15): a calm screen instead of a failure. */
    readonly takenDown = signal(false);
    /** The deck of the address, known even when the material cannot be read. */
    readonly deckId = signal<string | null>(null);
    readonly deleting = signal(false);
    readonly deleteMessage = signal<string | null>(null);
    readonly positionError = signal(false);
    /** The server offers AI generation: «Упражнения с ИИ» is shown only then (fail closed: unknown counts as unavailable). */
    readonly aiAvailable = signal(false);

    /** «Попросить Мнему…» is about this material at its head (the server pins it). */
    protected readonly askContext = computed<IntentContext | null>(() => {
        const item = this.item();
        return item === null ? null : { kind: 'MATERIAL', memberKey: item.memberKey };
    });
    protected readonly askBuilderQuery = computed(() => ({ members: this.item()?.memberKey ?? '' }));
    protected readonly askEditQuery = computed(() => ({ ordinal: String(this.selectedOrdinal() ?? '') }));

    private readonly route = inject(ActivatedRoute);
    private readonly router = inject(Router);
    private readonly decks = inject(OwnDecksApiService);
    private readonly items = inject(ItemApiService);
    private readonly navigationApi = inject(MaterialNavigationApiService);
    private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private loadSubscription: Subscription | null = null;
    private navigationSubscription: Subscription | null = null;
    private deletionSubscription: Subscription | null = null;
    private readonly capabilityApi = inject(CapabilitiesApiService);
    private readonly destroyRef = inject(DestroyRef);
    private deletionSnapshot: { version: string; revisionId: string; ordinal: number } | null = null;
    private pendingDeletion: { deckId: string; memberKey: string; itemRevisionId: string;
        version: string; revisionId: string; ordinal: number; commandId: string } | null = null;

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.load());
        this.capabilityApi.read().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: capabilities => this.aiAvailable.set(capabilities.aiGeneration.available),
            error: () => this.aiAvailable.set(CAPABILITIES_UNAVAILABLE.aiGeneration.available)
        });
    }

    load(): void {
        const deckId = this.route.snapshot.paramMap.get('deckId');
        const memberKey = this.route.snapshot.paramMap.get('memberKey');
        if (this.deleting() || this.pendingDeletion) {
            if (deckId === this.deckId() && memberKey === this.item()?.memberKey) return;
            // Уход с материала отменяет локальное ожидание; исход команды проверится новым чтением при возврате.
            this.deletionSubscription?.unsubscribe();
            this.pendingDeletion = null;
            this.deleting.set(false);
        }
        this.loadSubscription?.unsubscribe();
        this.navigationSubscription?.unsubscribe();
        this.navigation.set(null);
        this.navigationFailure.set(false);
        this.deck.set(null);
        this.item.set(null);
        this.deckId.set(deckId);
        this.takenDown.set(false);
        this.selectedOrdinal.set(null);
        this.deletionSnapshot = null;
        this.positionError.set(false);
        this.deleteMessage.set(null);
        if (deckId === null || memberKey === null) { this.failure.set(true); this.loading.set(false); return; }
        this.loading.set(true);
        this.failure.set(false);
        this.loadSubscription = forkJoin({ deck: this.decks.detail(deckId), content: this.items.read(deckId, memberKey) })
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.deck.set(result.deck);
                this.item.set(result.content);
                this.setPosition(result.content);
                this.loading.set(false);
                this.loadNavigation(result.content);
                afterNextRender(() => {
                    const active = this.host.nativeElement.ownerDocument.activeElement;
                    if (active === this.host.nativeElement.ownerDocument.body || (active && this.host.nativeElement.contains(active))) {
                        this.heading()?.nativeElement.focus();
                    }
                }, { injector: this.injector });
            },
            error: (error: unknown) => {
                if (isTakenDown(error)) this.takenDown.set(true); else this.failure.set(true);
                this.loading.set(false);
            }
        });
    }

    private neighbourLink(memberKey: string | null | undefined): readonly string[] | null {
        const deckId = this.deckId();
        return memberKey && deckId && !this.deleting() && this.pendingDeletion === null ? ['/decks', deckId, 'materials', memberKey] : null;
    }

    private loadNavigation(item: ItemDetail): void {
        this.navigationSubscription = this.navigationApi.read(item.deckId, item.memberKey)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: navigation => {
                    if (navigation.deckRevisionId !== item.deckRevisionId || navigation.deckVersion !== item.deckVersion
                        || navigation.ordinal !== item.ordinal) {
                        this.navigationFailure.set(true);
                        return;
                    }
                    this.navigation.set(navigation);
                },
                error: () => this.navigationFailure.set(true)
            });
    }

    deleteItem(): void {
        const deck = this.deck(); const item = this.item(); const snapshot = this.deletionSnapshot;
        if (this.deleting() || !deck || !item || !snapshot) return;
        const command = this.pendingDeletion ?? { deckId: deck.deckId, memberKey: item.memberKey,
            itemRevisionId: item.itemRevisionId, ...snapshot, commandId: newCommandId() };
        this.pendingDeletion = command;
        this.deleting.set(true);
        this.deleteMessage.set(null);
        this.deletionSubscription = this.items.delete(command.deckId, command.memberKey, command.version, command.revisionId,
            command.itemRevisionId, command.ordinal, command.commandId)
            .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                next: () => {
                    this.pendingDeletion = null;
                    this.deletionSnapshot = null;
                    void this.router.navigate(['/decks', command.deckId]);
                },
                error: (error: unknown) => {
                    this.deleting.set(false);
                    const uncertain = !(error instanceof HttpErrorResponse) || error.status === 0 || error.status >= 500;
                    if (!uncertain) this.pendingDeletion = null;
                    if (error instanceof HttpErrorResponse && (error.status === 412 || error.status === 404)) {
                        this.deletionSnapshot = null;
                        this.selectedOrdinal.set(null);
                        this.positionError.set(true);
                    }
                    this.deleteMessage.set(uncertain
                        ? 'Удаление не подтверждено. Повторите удержание: будет отправлена та же команда.'
                        : 'Материал не удалён. Обновите страницу и повторите попытку.');
                }
            });
    }

    private setPosition(item: ItemDetail): void {
        // The current read binds position, document and write preconditions to one server snapshot.
        if (item.ordinal === null) { this.positionError.set(true); return; }
        this.selectedOrdinal.set(item.ordinal);
        this.deletionSnapshot = { version: item.deckVersion, revisionId: item.deckRevisionId, ordinal: item.ordinal };
    }
}

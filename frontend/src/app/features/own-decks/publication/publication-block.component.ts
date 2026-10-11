import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';

import { ToastService } from '../../../core/notifications/toast.service';
import { AccessLevelComponent } from '../../../shared/access-level.component';
import type { DeckVisibility } from '../own-deck.models';
import { PublicationApiService } from './publication-api.service';
import { PublicationChecklistComponent } from './publication-checklist.component';
import {
    PublicationCommand,
    PublicationState,
    RELEASE_NOTE_MAX_CODE_POINTS,
    newPublicationCommandId,
    publicationFailureOf,
    releaseNoteLength,
    releaseNoteValue
} from './publication.models';
import { PUBLICATION_TEXT, publicationFailureText, unpublishedChangesText } from './publication.text';

type PublicationView =
    | { readonly phase: 'loading' }
    | { readonly phase: 'error' }
    | { readonly phase: 'ready'; readonly state: PublicationState };

let nextBlock = 0;

/**
 * Who may read the deck and what the readers see, in the Deck hub. It loads its own request: a failure shows a retry here and
 * never hides the hub. A private deck shows one quiet line and «Сделать публичной…» (the checklist, an inline panel). A shared
 * deck shows its level, the link with a copy action, the time of the last publication and, when the deck changed after it,
 * «Изменения для учеников не опубликованы (N)» with «Опубликовать обновление», which opens a small form with the optional «Что нового».
 */
@Component({
    selector: 'app-publication-block',
    imports: [DatePipe, AccessLevelComponent, PublicationChecklistComponent],
    templateUrl: './publication-block.component.html',
    styleUrl: './publication-block.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicationBlockComponent {
    readonly deckId = input.required<string>();
    /** The deck's real access level, as soon as it is known. */
    readonly levelChange = output<DeckVisibility>();
    /** The checklist asks for the deck editor to add a description. */
    readonly editDescription = output<void>();
    /** Bump to re-read the state after the deck itself changed (a saved description, for example). */
    readonly reloadToken = input(0);

    protected readonly text = PUBLICATION_TEXT;
    protected readonly uid = `mn-publication-${nextBlock++}`;
    protected readonly noteMax = RELEASE_NOTE_MAX_CODE_POINTS;
    protected readonly view = signal<PublicationView>({ phase: 'loading' });
    protected readonly updateOpen = signal(false);
    protected readonly checklistOpen = signal(false);
    protected readonly note = signal('');
    protected readonly publishing = signal(false);
    protected readonly problem = signal<string | null>(null);
    protected readonly copyFailed = signal(false);
    /** Spoken once when the note crosses the limit («Лишних символов: N»); empty again when it fits. */
    protected readonly limitStatus = signal('');

    protected readonly noteLength = computed(() => releaseNoteLength(this.note()));
    protected readonly noteTooLong = computed(() => this.noteLength() > RELEASE_NOTE_MAX_CODE_POINTS);
    protected readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
    protected readonly noteField = viewChild<ElementRef<HTMLTextAreaElement>>('noteField');
    protected readonly linkField = viewChild<ElementRef<HTMLInputElement>>('linkField');
    protected readonly updateButton = viewChild<ElementRef<HTMLButtonElement>>('updateButton');
    protected readonly makePublicButton = viewChild<ElementRef<HTMLButtonElement>>('makePublicButton');

    private readonly api = inject(PublicationApiService);
    private readonly toasts = inject(ToastService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private loading: Subscription | null = null;
    /** A replayed publication was reported: close the checklist (and focus the heading) once the re-read state is public. */
    private closeWhenPublic = false;
    /** The exact command of an update whose outcome is unknown; retried with the same id. */
    private pending: { readonly command: PublicationCommand; readonly rowVersion: string } | null = null;

    constructor() {
        effect(() => {
            const deckId = this.deckId();
            this.reloadToken();
            untracked(() => this.load(deckId));
        });
        this.destroyRef.onDestroy(() => this.loading?.unsubscribe());
    }

    /** Absolute address of the shared deck, or `null` while it is private. */
    protected absoluteLink(state: PublicationState): string | null {
        return state.link === null ? null : new URL(state.link, window.location.origin).toString();
    }

    protected shownLink(state: PublicationState): string {
        return this.absoluteLink(state)?.replace(/^https?:\/\//, '') ?? '';
    }

    protected unchangedText(state: PublicationState): string {
        return state.unpublishedChanges! > 0 ? unpublishedChangesText(state.unpublishedChanges!)
            : state.headRevisionId !== state.publishedRevisionId ? PUBLICATION_TEXT.headOnly : PUBLICATION_TEXT.allPublished;
    }

    protected hasUpdate(state: PublicationState): boolean {
        return (state.unpublishedChanges ?? 0) > 0 || (state.publishedRevisionId !== null && state.headRevisionId !== state.publishedRevisionId);
    }

    protected canMakePublic(state: PublicationState): boolean {
        return state.visibility !== 'PUBLIC';
    }

    protected load(deckId: string = this.deckId()): void {
        this.loading?.unsubscribe();
        if (this.view().phase !== 'ready') this.view.set({ phase: 'loading' });
        this.loading = this.api.read(deckId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: state => this.apply(state),
            error: () => { if (this.view().phase !== 'ready') this.view.set({ phase: 'error' }); }
        });
    }

    protected refresh(): void {
        this.load();
    }

    protected openUpdate(): void {
        this.updateOpen.set(true);
        this.problem.set(null);
        afterNextRender(() => this.noteField()?.nativeElement.focus(), { injector: this.injector });
    }

    protected closeUpdate(): void {
        this.updateOpen.set(false);
        this.problem.set(null);
        afterNextRender(() => this.updateButton()?.nativeElement.focus(), { injector: this.injector });
    }

    protected openChecklist(): void {
        this.checklistOpen.set(true);
    }

    protected closeChecklist(): void {
        this.checklistOpen.set(false);
        afterNextRender(() => this.makePublicButton()?.nativeElement.focus(), { injector: this.injector });
    }

    protected onChecklistPublished(state: PublicationState): void {
        this.checklistOpen.set(false);
        this.apply(state);
        afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
    }

    protected onChecklistReplayed(): void {
        this.closeWhenPublic = true;
    }

    protected onNote(event: Event): void {
        const wasOver = this.noteTooLong();
        this.note.set((event.target as HTMLTextAreaElement).value);
        this.problem.set(null);
        const over = this.noteLength() - RELEASE_NOTE_MAX_CODE_POINTS;
        if (over <= 0) this.limitStatus.set('');
        else if (!wasOver) this.limitStatus.set(PUBLICATION_TEXT.overBy(over));
    }

    protected publishUpdate(state: PublicationState): void {
        if (this.publishing() || this.noteTooLong()) return;
        const command = this.commandFor(state);
        this.pending = { command, rowVersion: state.rowVersion };
        this.publishing.set(true);
        this.problem.set(null);
        this.api.save(this.deckId(), state.rowVersion, command).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.pending = null;
                this.publishing.set(false);
                this.updateOpen.set(false);
                this.note.set('');
                this.limitStatus.set('');
                this.toasts.echo(PUBLICATION_TEXT.updatePublished);
                if (result.replayed) this.load(); else this.apply(result.acknowledgement.publication);
                afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
            },
            error: (error: unknown) => this.failUpdate(error)
        });
    }

    // TODO(#434): replace this local copy button with `app-share-button` (Share/12) once it is merged into this branch.
    protected async copyLink(state: PublicationState): Promise<void> {
        const link = this.absoluteLink(state);
        if (link === null) return;
        this.copyFailed.set(false);
        try {
            await navigator.clipboard.writeText(link);
            this.toasts.echo(PUBLICATION_TEXT.linkCopied);
        } catch {
            this.copyFailed.set(true);
            afterNextRender(() => {
                const field = this.linkField()?.nativeElement;
                field?.focus();
                field?.setSelectionRange(0, field.value.length);
            }, { injector: this.injector });
        }
    }

    private failUpdate(error: unknown): void {
        const failure = publicationFailureOf(error);
        this.publishing.set(false);
        this.problem.set(publicationFailureText(failure));
        // Only a network error or a server fault leaves the outcome unknown (the same command is retried); an answer is final.
        if (failure !== null && failure.status >= 400 && failure.status < 500) {
            this.pending = null;
            if (failure.status === 412 || failure.status === 409) this.load();
        }
    }

    private commandFor(state: PublicationState): PublicationCommand {
        const releaseNote = releaseNoteValue(this.note());
        const pending = this.pending;
        if (pending !== null && pending.rowVersion === state.rowVersion
            && pending.command.publish?.releaseNote === releaseNote
            && pending.command.publish.expectedHeadRevisionId === state.headRevisionId) {
            return pending.command;
        }
        return {
            commandId: newPublicationCommandId(),
            visibility: state.visibility,
            metadata: state.metadata,
            requestsEnabled: state.requestsEnabled,
            publish: { expectedHeadRevisionId: state.headRevisionId, releaseNote }
        };
    }

    private apply(state: PublicationState): void {
        this.view.set({ phase: 'ready', state });
        if (this.closeWhenPublic) {
            this.closeWhenPublic = false;
            if (state.visibility === 'PUBLIC' && this.checklistOpen()) {
                this.checklistOpen.set(false);
                afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
            }
        }
        this.levelChange.emit(state.visibility.toLowerCase() as DeckVisibility);
    }
}

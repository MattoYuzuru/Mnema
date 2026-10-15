import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, inject, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { ToastService } from '../../../core/notifications/toast.service';
import { SegmentedChoiceComponent, SegmentedOption } from '../../../shared/segmented-choice.component';
import { ACCESS_TEXT, levelChangeLines } from '../access/access.text';
import { PublicationApiService } from './publication-api.service';
import {
    PublicationCommand,
    PublicationLevel,
    PublicationState,
    newPublicationCommandId,
    publicationFailureOf
} from './publication.models';
import { publicationFailureText } from './publication.text';

let nextControls = 0;

/** The levels in the order of the product table (docs/product/community-decks.md, «Доступ»). */
const LEVELS: readonly PublicationLevel[] = ['PRIVATE', 'LINK', 'INVITE', 'PUBLIC'];

/** Выбор доступа сохраняется сразу; «Публичная» открывает обязательный чек-лист. Неизвестный исход повторяет точную команду. */
@Component({
    selector: 'app-access-controls',
    imports: [SegmentedChoiceComponent],
    templateUrl: './access-controls.component.html',
    styleUrl: './access-controls.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AccessControlsComponent {
    readonly state = input.required<PublicationState>();
    /** Обязательный чек-лист открывается при выборе публичного доступа. */
    readonly checklistOpen = input(false);
    /** A command was applied: the new state. */
    readonly saved = output<PublicationState>();
    /** An exact retry was answered with the original acknowledgement: the host re-reads the state. */
    readonly replayed = output<void>();
    /** The server said the deck changed: the host re-reads the state. */
    readonly refresh = output<void>();
    readonly openChecklist = output<void>();
    readonly closeChecklist = output<void>();

    protected readonly text = ACCESS_TEXT;
    protected readonly uid = `mn-access-controls-${nextControls++}`;
    private readonly stored = computed(() => this.state().visibility);
    /** The level picked in the picker; it follows the stored level (after a save, a re-read or the checklist) until the owner picks another. */
    protected readonly staged = linkedSignal<PublicationLevel, PublicationLevel | null>({ source: this.stored, computation: level => level });
    protected readonly busy = signal(false);
    protected readonly problem = signal<string | null>(null);
    protected readonly unknownOutcome = signal(false);

    private restoringChoice = false;
    private readonly picker = viewChild<SegmentedChoiceComponent<PublicationLevel>>(SegmentedChoiceComponent);

    protected readonly options: readonly SegmentedOption<PublicationLevel>[] = LEVELS.map(level => ({
        value: level, label: ACCESS_TEXT.levelNames[level], hint: ACCESS_TEXT.levelHints[level]
    }));
    protected readonly changing = computed(() => {
        const staged = this.staged();
        return staged !== null && staged !== this.state().visibility ? staged : null;
    });
    protected readonly lines = computed(() => {
        const to = this.changing();
        return to === null ? [] : levelChangeLines(this.state().visibility, to);
    });

    private readonly api = inject(PublicationApiService);
    private readonly toasts = inject(ToastService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly injector = inject(Injector);
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    /** The exact command of an attempt whose outcome is unknown; retried with the same id. */
    private pending: { readonly command: PublicationCommand; readonly rowVersion: string; readonly state: PublicationState; readonly done: string; readonly level: boolean } | null = null;

    /** Puts the focus on the picker (the selected segment), e.g. after the checklist was dismissed. */
    focusPicker(): void {
        afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('input[type=radio]:checked')?.focus(), { injector: this.injector });
    }

    /** После закрытия чек-листа фокус возвращается на выбор доступа. */
    cancelPublicChoice(): void { this.restoreStoredChoice(); this.focusPicker(); }

    protected pick(level: PublicationLevel | null): void {
        if (level === null || this.busy() || this.unknownOutcome() || this.restoringChoice) return;
        if (level !== 'PUBLIC' && this.checklistOpen()) this.closeChecklist.emit();
        this.staged.set(level);
        this.problem.set(null);
        if (level === 'PUBLIC' && this.state().visibility !== 'PUBLIC') this.openChecklist.emit();
        else this.apply();
    }

    protected retryPending(): void {
        const pending = this.pending;
        if (pending === null || this.busy()) return;
        this.send(pending.command, pending.state, pending.done, pending.level);
    }

    protected apply(): void {
        const target = this.changing();
        if (this.busy() || target === null) return;
        if (target === 'PUBLIC') return;
        const state = this.state();
        this.send(this.reuseOrBuild(state, {
            commandId: '',
            visibility: target,
            metadata: state.metadata,
            requestsEnabled: state.requestsEnabled,
            // The first publication happens on leaving «Приватная»; later returns publish the current head again.
            publish: state.visibility === 'PRIVATE' ? { expectedHeadRevisionId: state.headRevisionId, releaseNote: null } : null
        }), state, ACCESS_TEXT.levelChanged(ACCESS_TEXT.levelNames[target]), true);
    }

    protected toggleRequests(event: Event): void {
        const input = event.target as HTMLInputElement;
        const state = this.state();
        if (this.busy() || this.unknownOutcome()) { input.checked = state.requestsEnabled; return; }
        this.send(this.reuseOrBuild(state, {
            commandId: '', visibility: state.visibility, metadata: state.metadata, requestsEnabled: input.checked, publish: null
        }), state, ACCESS_TEXT.requestsSaved, false, input);
    }

    /** A busy switch ignores the click instead of losing the focus (`aria-disabled`, never `disabled`). */
    protected hold(event: Event): void {
        if (this.busy() || this.unknownOutcome()) event.preventDefault();
    }

    private restoreStoredChoice(): void {
        const level = this.state().visibility;
        this.staged.set(level);
        // Синхронный отказ может прийти раньше первого рендера выбранного значения: возвращаем также модель нативной группы.
        this.restoringChoice = true;
        this.picker()?.value.set(level);
        this.restoringChoice = false;
    }

    private reuseOrBuild(state: PublicationState, draft: PublicationCommand): PublicationCommand {
        const pending = this.pending;
        if (pending !== null && pending.rowVersion === state.rowVersion
            && JSON.stringify({ ...pending.command, commandId: '' }) === JSON.stringify(draft)) return pending.command;
        return { ...draft, commandId: newPublicationCommandId() };
    }

    private send(command: PublicationCommand, state: PublicationState, done: string, level: boolean, input?: HTMLInputElement): void {
        this.pending = { command, rowVersion: state.rowVersion, state, done, level };
        this.unknownOutcome.set(false);
        this.busy.set(true);
        this.problem.set(null);
        this.api.save(state.deckId, state.rowVersion, command).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.pending = null;
                this.busy.set(false);
                this.toasts.echo(done);
                if (result.replayed) {
                    this.restoreStoredChoice();
                    this.replayed.emit();
                } else {
                    this.staged.set(result.acknowledgement.publication.visibility);
                    this.saved.emit(result.acknowledgement.publication);
                }
                if (level) this.focusPicker();
            },
            error: (error: unknown) => {
                const failure = publicationFailureOf(error);
                this.busy.set(false);
                this.problem.set(publicationFailureText(failure));
                if (input !== undefined) input.checked = state.requestsEnabled;
                // Only a lost connection or a server fault leaves the outcome unknown (the same command is retried); an answer is final.
                if (failure !== null && failure.status >= 400 && failure.status < 500) {
                    this.pending = null;
                    this.restoreStoredChoice();
                    if (failure.status === 412 || failure.status === 409) this.refresh.emit();
                } else this.unknownOutcome.set(true);
                if (level) this.focusPicker();
            }
        });
    }
}

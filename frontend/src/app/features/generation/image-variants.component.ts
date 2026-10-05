import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal, untracked } from '@angular/core';

import { ToggletipComponent } from '../../shared/toggletip.component';
import { SHARE_ALIKE_HINT, SHARE_ALIKE_MARK, candidateByline } from './generation-view';
import { ImageCandidate, MAX_CANDIDATES } from './generation.models';
import { ImageCandidateThumbComponent } from './image-candidate-thumb.component';

let nextGroup = 0;

/**
 * The variants of a found image (AI-10, #296): the READY candidates of a slot as a native radio group, the chosen one checked, and one
 * button, «Использовать это изображение», that commits the local choice. Arrow keys (and a click on a card) only move the local choice:
 * a keyboard or screen reader user can walk all twelve variants without a single request, and the server is written once, by the button.
 *
 * Each card carries its picture (the owner's own asset), «Источник · Автор», the license, a «BY-SA» mark with its explanation for a
 * share-alike license, and a link to the image's page at the source (opened in a new tab; only `https:` ever reaches `href`, the parser
 * drops the rest). The link and the toggletip are outside the `<label>` (interactive content does not belong in a label's name). While the
 * choice is saved the group is `aria-disabled` rather than `disabled` (a disabled input drops keyboard focus) and a status says so.
 */
@Component({
    selector: 'app-image-variants',
    imports: [ImageCandidateThumbComponent, ToggletipComponent],
    templateUrl: './image-variants.component.html',
    styleUrl: './image-variants.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ImageVariantsComponent {
    /** The candidates of the slot, in the order they were found; only the READY ones are offered. */
    readonly candidates = input.required<readonly ImageCandidate[]>();
    /** The candidate whose choice is being saved, or `null`. */
    readonly selecting = input<string | null>(null);
    /** Another command on the artifact is in flight. */
    readonly busy = input(false);
    readonly error = input<string | null>(null);
    /** «Использовать это изображение»: the candidate the owner picked. */
    readonly commit = output<string>();

    private readonly uid = `mn-variants-${nextGroup++}`;
    protected readonly name = `${this.uid}-radio`;
    protected readonly errorId = `${this.uid}-error`;
    protected readonly ready = computed(() => this.candidates().filter(candidate => candidate.state === 'READY').slice(0, MAX_CANDIDATES));
    protected readonly pending = computed(() => this.busy() || this.selecting() !== null);
    /** The radio the owner moved to, until the server's answer says which candidate is chosen. */
    protected readonly local = signal<string | null>(null);
    protected readonly current = computed(() => this.ready().find(candidate => candidate.chosen)?.candidateId ?? null);
    protected readonly picked = computed(() => this.local() ?? this.current());
    protected readonly changed = computed(() => this.local() !== null && this.local() !== this.current());
    protected readonly shareAlikeMark = SHARE_ALIKE_MARK;
    protected readonly shareAlikeHint = SHARE_ALIKE_HINT;
    protected readonly byline = candidateByline;

    constructor() {
        // A new answer (another chosen candidate, the artifact read again) ends the local choice: the radios show what the server holds.
        effect(() => {
            this.candidates();
            untracked(() => this.local.set(null));
        });
    }

    protected isChecked(candidate: ImageCandidate): boolean {
        return this.picked() === candidate.candidateId;
    }

    protected onClick(event: Event, candidate: ImageCandidate): void {
        if (this.pending()) { event.preventDefault(); return; }
        this.local.set(candidate.candidateId);
    }

    protected use(): void {
        const local = this.local();
        if (this.pending() || local === null || local === this.current()) return;
        this.commit.emit(local);
    }
}

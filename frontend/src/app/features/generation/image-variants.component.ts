import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { ToggletipComponent } from '../../shared/toggletip.component';
import { SHARE_ALIKE_HINT, SHARE_ALIKE_MARK, candidateByline } from './generation-view';
import { ImageCandidate, MAX_CANDIDATES } from './generation.models';
import { ImageCandidateThumbComponent } from './image-candidate-thumb.component';

let nextGroup = 0;

/**
 * The variants of a found image (AI-10, #296): the READY candidates of a slot as a native radio group, the chosen one checked. Each card
 * carries its picture (the owner's own asset), «Источник · Автор», the license, a «BY-SA» mark with its explanation for a share-alike
 * license, and a link to the image's page at the source (opened in a new tab; only `https:` ever reaches `href`, the parser drops the rest).
 *
 * The link and the toggletip are outside the `<label>` (interactive content does not belong in a label's name). While a choice is being
 * sent the group is `aria-disabled` rather than `disabled`: a disabled input drops keyboard focus, and the arrow keys would have nowhere to
 * come back to. A change that arrives while it is disabled is cancelled; one the server refused puts the radio back where the server holds it (the parent clears `selecting`).
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
    /** The candidate whose choice is being sent: it shows as checked until the server's answer says which one is chosen. */
    readonly selecting = input<string | null>(null);
    /** Another command on the artifact is in flight. */
    readonly busy = input(false);
    readonly error = input<string | null>(null);
    readonly chosen = output<string>();

    private readonly uid = `mn-variants-${nextGroup++}`;
    protected readonly name = `${this.uid}-radio`;
    protected readonly errorId = `${this.uid}-error`;
    protected readonly ready = computed(() => this.candidates().filter(candidate => candidate.state === 'READY').slice(0, MAX_CANDIDATES));
    protected readonly pending = computed(() => this.busy() || this.selecting() !== null);
    protected readonly shareAlikeMark = SHARE_ALIKE_MARK;
    protected readonly shareAlikeHint = SHARE_ALIKE_HINT;
    protected readonly byline = candidateByline;

    protected isChecked(candidate: ImageCandidate): boolean {
        const selecting = this.selecting();
        return selecting === null ? candidate.chosen : selecting === candidate.candidateId;
    }

    protected onClick(event: Event, candidate: ImageCandidate): void {
        if (this.pending()) { event.preventDefault(); return; }
        if (candidate.chosen) return;
        this.chosen.emit(candidate.candidateId);
    }
}

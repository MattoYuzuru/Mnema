import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { describeTurnAsk, describeTurnStatus, formatWorkshopStart, turnFailureReason } from './generation-view';
import { ArtifactDetail, ArtifactTurn } from './generation.models';

/** One line of the history: the original, or a turn. */
export interface HistoryEntry {
    readonly key: string;
    readonly label: string;
    readonly status: string | null;
    readonly time: string | null;
    readonly datetime: string | null;
    readonly current: boolean;
    /** The revision «Вернуть к этой версии» goes to; `null` when it cannot (the current one, or a turn that made none). */
    readonly revertTo: string | null;
}

/** «не удалось — Мнема отказалась переписывать этот фрагмент»: how a turn stands, and for a failed one the reason in words. */
function turnStatusWithReason(turn: ArtifactTurn): string {
    const status = describeTurnStatus(turn);
    return turn.status === 'FAILED' ? `${status} — ${turnFailureReason(turn.errorCode).replace(/\.$/u, '')}` : status;
}

/**
 * The history of the edits of one proposal (AI-11, #293): the original and every turn, newest last, each with the version it made, and
 * «Вернуть к этой версии» on the ones the artifact can go back to. The Workshop shows it under a material and under an exercise alike
 * (the revisions and the turns are the same shape); the host owns the command, this only lists and reports the choice.
 */
@Component({
    selector: 'app-edit-history',
    templateUrl: './edit-history.component.html',
    styleUrl: './edit-history.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class EditHistoryComponent {
    readonly detail = input<ArtifactDetail | null>(null);
    /** The revision the artifact points at now. */
    readonly current = input<string | null>(null);
    /** A command is in flight, or the artifact cannot go back now: the buttons stay in place but do nothing. */
    readonly disabled = input(false);

    /** The revision the user chose to go back to. */
    readonly revert = output<string>();

    protected readonly entries = computed<readonly HistoryEntry[]>(() => {
        const detail = this.detail();
        if (detail === null || detail.turns.length === 0) return [];
        const current = this.current();
        const listed = new Set(detail.revisions.map(revision => revision.revisionId));
        const target = (revisionId: string | null): string | null => revisionId !== null && revisionId !== current && listed.has(revisionId) ? revisionId : null;
        const first = detail.revisions[0];
        const entries: HistoryEntry[] = [];
        if (first !== undefined) {
            entries.push({ key: first.revisionId, label: 'Исходная версия', status: null, time: formatWorkshopStart(first.createdAt),
                datetime: first.createdAt, current: first.revisionId === current, revertTo: target(first.revisionId) });
        }
        for (const turn of detail.turns) {
            entries.push({ key: turn.turnId, label: describeTurnAsk(turn), status: turnStatusWithReason(turn), time: formatWorkshopStart(turn.createdAt),
                datetime: turn.createdAt, current: turn.status === 'APPLIED' && turn.resultRevisionId === current,
                revertTo: turn.status === 'APPLIED' ? target(turn.resultRevisionId) : null });
        }
        return entries;
    });

    protected pick(entry: HistoryEntry): void {
        if (entry.revertTo === null || this.disabled()) return;
        this.revert.emit(entry.revertTo);
    }
}

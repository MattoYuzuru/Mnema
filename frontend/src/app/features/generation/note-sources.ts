import { CaptureNote } from '../authoring/authoring.models';
import { isCanonicalEntityId } from '../own-decks/own-deck.models';
import { Effort, MAX_SOURCES, NoteOverrides, SpecSource } from './generation.models';

/**
 * Notes of «На потом» as sources of a Materials request (AI-08, #290): the query parameter that carries them from the
 * capture page to the composer, the chip text, and the per-note override drafts the composer edits.
 */

/** `/decks/:deckId/materials/new?notes=<id>,<id>`: the notes picked on the capture page. At most {@link MAX_SOURCES}. */
export const NOTES_QUERY_PARAM = 'notes';

/** How many notes one request may carry (`generationSpec.MATERIALS.sources`: 0..20). */
export const MAX_NOTES = MAX_SOURCES;

/** A pinned source shown as a chip. `label` is the text the user recognises it by. */
export interface ComposerSource { readonly spec: SpecSource; readonly label: string; }

/** A stable identity of a chip: the note id, or the member key of a style example. */
export function sourceKey(source: ComposerSource): string {
    return source.spec.type === 'NOTE' ? source.spec.noteId : source.spec.memberKey;
}

export function serializeNoteIds(noteIds: readonly string[]): string {
    return noteIds.join(',');
}

/** The distinct, well-formed ids of the query value, in order; `rejected` counts what was not usable (malformed or over the limit; a repeated id is simply one note). */
export function parseNoteIds(value: string | null): { readonly ids: readonly string[]; readonly rejected: number } {
    if (value === null || value.trim().length === 0) return { ids: [], rejected: 0 };
    const seen = new Set<string>();
    let rejected = 0;
    for (const part of value.split(',')) {
        const id = part.trim().toLowerCase();
        if (!isCanonicalEntityId(id)) rejected += 1;
        else if (seen.has(id)) continue;
        else if (seen.size < MAX_NOTES) seen.add(id);
        else rejected += 1;
    }
    return { ids: [...seen], rejected };
}

const EXCERPT_LENGTH = 60;

/** The start of a note on one line: the chip text and the row label of the per-note settings. */
export function noteExcerpt(text: string): string {
    const line = text.replace(/\s+/gu, ' ').trim();
    return line.length <= EXCERPT_LENGTH ? line : `${line.slice(0, EXCERPT_LENGTH).trimEnd()}…`;
}

export function noteSource(note: CaptureNote): ComposerSource {
    return { spec: { role: 'SOURCE', type: 'NOTE', noteId: note.noteId, noteRowVersion: note.rowVersion }, label: noteExcerpt(note.text) };
}

/** Why a note cannot be a source here; `null` when it can. Only notes of the deck that are not archived are offered. */
export type NoteRefusal = 'ARCHIVED' | 'OTHER_DECK';

export function refusalOf(note: CaptureNote, deckId: string): NoteRefusal | null {
    if (note.deckId !== deckId.toLowerCase()) return 'OTHER_DECK';
    return note.archived ? 'ARCHIVED' : null;
}

/** The calm sentence for notes that could not be added; `null` when every note was usable. */
export function refusalMessage(archived: number, elsewhere: number, missing: number): string | null {
    const parts: string[] = [];
    if (archived > 0) parts.push(archived === 1 ? 'Одна заметка уже в архиве.' : `Заметок в архиве: ${archived}.`);
    if (elsewhere > 0) parts.push(elsewhere === 1 ? 'Одна заметка из другой колоды.' : `Заметок из другой колоды: ${elsewhere}.`);
    if (missing > 0) parts.push(missing === 1 ? 'Одну заметку не удалось найти.' : `Заметок не удалось найти: ${missing}.`);
    return parts.length === 0 ? null : `${parts.join(' ')} В запрос они не попали, остальные заметки на месте.`;
}

// --- Per-note overrides (the drafts the composer edits) ---

/** What the user chose for one note; `null` means «как для всех» (the session default). */
export interface NoteOverrideDraft {
    readonly effort: Effort | null;
    readonly imageSearch: boolean | null;
    readonly audio: boolean | null;
}

export type NoteOverrideMap = Readonly<Record<string, NoteOverrideDraft>>;

export const NO_OVERRIDE: NoteOverrideDraft = { effort: null, imageSearch: null, audio: null };

export function isCustomized(draft: NoteOverrideDraft | undefined): boolean {
    return draft !== undefined && (draft.effort !== null || draft.imageSearch !== null || draft.audio !== null);
}

/** How many notes carry at least one own setting. */
export function customizedCount(overrides: NoteOverrideMap, noteIds: readonly string[]): number {
    return noteIds.filter(id => isCustomized(overrides[id])).length;
}

/** «2 заметки настроены отдельно»; for none, the plain statement that every note follows the common settings. */
export function customizedSummary(count: number): string {
    if (count === 0) return 'Все заметки — с общими настройками';
    const ending = count % 100;
    const last = count % 10;
    if (last === 1 && ending !== 11) return `${count} заметка настроена отдельно`;
    if (last >= 2 && last <= 4 && (ending < 12 || ending > 14)) return `${count} заметки настроены отдельно`;
    return `${count} заметок настроено отдельно`;
}

/**
 * The sparse wire overrides of a draft: only what the user changed. Media is requested only where the capability exists, and the
 * audio language and voice are the session's. `undefined` when nothing remains (an empty object is never sent).
 */
export function overridesOf(draft: NoteOverrideDraft | undefined, session: {
    readonly audioLang: string; readonly audioVoice: 'any' | 'female' | 'male';
}, available: { readonly image: boolean; readonly audio: boolean }): NoteOverrides | undefined {
    if (draft === undefined) return undefined;
    // A switch the server does not offer is not sent at all: it would be refused for the whole request.
    const audio = draft.audio === null || !available.audio ? undefined : { enabled: draft.audio, lang: session.audioLang,
        voice: session.audioVoice === 'any' ? null : session.audioVoice };
    const imageSearch = draft.imageSearch === null || !available.image ? undefined : draft.imageSearch;
    const media = audio === undefined && imageSearch === undefined ? undefined
        : { ...(audio === undefined ? {} : { audio }), ...(imageSearch === undefined ? {} : { imageSearch }) };
    if (draft.effort === null && media === undefined) return undefined;
    return { ...(draft.effort === null ? {} : { effort: draft.effort }), ...(media === undefined ? {} : { media }) };
}

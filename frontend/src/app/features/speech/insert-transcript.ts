/** A text field a transcript can be put into. */
export type TextTarget = HTMLTextAreaElement | HTMLInputElement;

export interface TargetSelection { readonly start: number; readonly end: number; }

/** Where the caret is now (`null` selection when the field has none to report). */
export function selectionOf(target: TextTarget): TargetSelection {
    const length = target.value.length;
    return { start: Math.min(target.selectionStart ?? length, length), end: Math.min(target.selectionEnd ?? length, length) };
}

/**
 * The text of the field with `transcript` put at `selection` (replacing what is selected), a space on each side where the neighbouring
 * text is not already separated, cut to the field's `maxLength`. `caret` is right after the inserted text.
 */
export function withTranscript(value: string, selection: TargetSelection, transcript: string, maxLength = -1): { readonly value: string; readonly caret: number; readonly inserted: string } {
    const start = Math.min(selection.start, value.length);
    const end = Math.min(Math.max(selection.end, start), value.length);
    const before = value.slice(0, start);
    const after = value.slice(end);
    const lead = before !== '' && !/\s$/u.test(before) ? ' ' : '';
    const trail = after !== '' && !/^\s/u.test(after) ? ' ' : '';
    let inserted = lead + transcript + trail;
    if (maxLength >= 0) {
        const room = Math.max(maxLength - before.length - after.length, 0);
        if (inserted.length > room) inserted = inserted.slice(0, room).trimEnd();
    }
    const next = before + inserted + after;
    return { value: next, caret: before.length + inserted.trimEnd().length, inserted };
}

/**
 * Puts the transcript into the field as if it was typed: the value is set and an `input` event is dispatched (so a bound signal, a form
 * control or a plain handler hears it), focus returns to the field, and the caret sits after the inserted text. Nothing is sent.
 */
export function insertTranscript(target: TextTarget, selection: TargetSelection, transcript: string): string {
    const maxLength = target.maxLength > 0 ? target.maxLength : -1;
    const result = withTranscript(target.value, selection, transcript, maxLength);
    target.value = result.value;
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.focus();
    target.setSelectionRange(result.caret, result.caret);
    return result.inserted.trim();
}

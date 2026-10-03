/**
 * Which proposals of a batch the user took the «Оставить» check off. «Изменить» leaves the Workshop for the editor and comes back to
 * a freshly opened page, so the choice is kept per session in `sessionStorage` (this tab only, gone with it). Storage can be absent or
 * refuse access (a private window, blocked site data): the choice then lives in memory for as long as the page script does.
 */
const PREFIX = 'mnema:exercise-review:';
const memory = new Map<string, readonly string[]>();

function storage(): Storage | null {
    try { return globalThis.sessionStorage ?? null; } catch { return null; }
}

/** The ids of the proposals that were unchecked in this session (empty when nothing was kept). */
export function loadUnchecked(sessionId: string): ReadonlySet<string> {
    try {
        const area = storage();
        if (area !== null) {
            const raw = area.getItem(PREFIX + sessionId);
            const parsed: unknown = raw === null ? [] : JSON.parse(raw);
            return new Set(Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string').slice(0, 200) : []);
        }
    } catch { /* storage refuses or holds nonsense: the memory copy is all there is */ }
    return new Set(memory.get(sessionId) ?? []);
}

/** Remembers the choice; the memory copy is the fallback only when storage cannot be used. */
export function saveUnchecked(sessionId: string, unchecked: ReadonlySet<string>): void {
    const ids = [...unchecked];
    memory.set(sessionId, ids);
    try {
        const area = storage();
        if (area === null) return;
        if (ids.length === 0) area.removeItem(PREFIX + sessionId); else area.setItem(PREFIX + sessionId, JSON.stringify(ids));
    } catch { /* the memory copy stays */ }
}

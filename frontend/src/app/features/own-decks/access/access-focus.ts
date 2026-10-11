/**
 * After a row left the list (decided, closed, revoked) the focus must not fall to the page: it goes to the first action of the row that
 * took its place, else of the closest row before, else to the section heading. `index` is where the row was.
 */
export function focusNearestAction(host: HTMLElement, index: number, fallback: HTMLElement): void {
    const rows = [...host.querySelectorAll<HTMLElement>('.roster-row')];
    const order = [...rows.slice(index), ...rows.slice(0, index).reverse()];
    for (const row of order) {
        const action = row.querySelector<HTMLElement>('button:not([aria-disabled="true"])');
        if (action !== null) { action.focus(); return; }
    }
    fallback.focus();
}

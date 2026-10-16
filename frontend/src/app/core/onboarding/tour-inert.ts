/**
 * Makes everything on the page `inert` except `target` and the elements in `keep` (the tour's own surfaces, the toast region),
 * which stay interactive. For every ancestor of the target, its siblings are marked, so the target and its subtree remain live.
 * Returns the function that undoes exactly what was marked: an element that was already inert stays inert. Set as an attribute,
 * so it needs no layout and works the same in a browser and in jsdom.
 */
export function inertOutside(target: Element, keep: readonly (Element | null)[]): () => void {
    const kept = keep.filter((element): element is Element => element !== null);
    closeOtherPopovers(target, kept);
    const marked: Element[] = [];
    for (let node: Element = target; node.parentElement !== null && node !== node.ownerDocument.body; node = node.parentElement) {
        for (const sibling of Array.from(node.parentElement.children)) {
            if (sibling === node || sibling.hasAttribute('inert')) continue;
            // A sibling that holds a kept surface (or is one) is not inert, or the surface would be.
            if (kept.some(element => sibling.contains(element) || element.contains(sibling))) continue;
            sibling.setAttribute('inert', '');
            marked.push(sibling);
        }
    }
    return () => marked.forEach(element => element.removeAttribute('inert'));
}

/**
 * An open popover (an action menu, a share list) outside the target would become inert while still open, and a click outside could no
 * longer dismiss it. Popovers that hold the target or a kept surface (the toast region) are left as they are.
 */
function closeOtherPopovers(target: Element, kept: readonly Element[]): void {
    const open = ((): HTMLElement[] => {
        try {
            return Array.from(target.ownerDocument.querySelectorAll<HTMLElement>(':popover-open'));
        } catch {
            // A browser without the Popover API has none to close (the selector is unknown to it).
            return [];
        }
    })();
    for (const popover of open) {
        if (popover.contains(target) || kept.some(element => popover.contains(element) || element.contains(popover))) continue;
        popover.hidePopover();
    }
}

/** The elements that are the tour's own: the tour host and every surface that marks itself with `data-tour-surface` (the onboarding host). */
export function tourSurfaces(document: Document): Element[] {
    return Array.from(document.querySelectorAll('[data-tour-surface]'));
}

/**
 * Another modal dialog (a complaint, a confirmation) is open, one that is not a surface of the tour. A dialog inside `target` (the
 * microphone consent of the composer, the prompt sheet of the Workshop) belongs to the step's own target and stays live with it,
 * so `ignoringInside` leaves it out.
 */
export function otherDialogOpen(document: Document, ignoringInside: Element | null = null): boolean {
    const surfaces = tourSurfaces(document);
    return Array.from(document.querySelectorAll('dialog[open]'))
        .some(dialog => !surfaces.some(surface => surface.contains(dialog)) && !(ignoringInside?.contains(dialog) ?? false));
}

/** A popover (a menu) is open that is not part of the tour's own surfaces or the toast region. */
export function otherPopoverOpen(document: Document): boolean {
    const surfaces = tourSurfaces(document);
    try {
        return Array.from(document.querySelectorAll(':popover-open')).some(popover => !surfaces.some(surface => surface.contains(popover)) && popover.tagName !== 'APP-TOAST-REGION' && !popover.classList.contains('toast-region'));
    } catch {
        // A browser without the Popover API has none open.
        return false;
    }
}

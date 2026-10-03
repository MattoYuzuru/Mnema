/**
 * Enter on a checkbox, radio or range inside a form submits it in most browsers. In a form that starts a priced request that must
 * never happen: the request starts only from its button. Bind it to `(keydown.enter)` of the form; text areas keep their own
 * handling (the Materials composer sends on Enter there by design), and a focused button is left alone (Enter presses it).
 */
export function blockImplicitSubmit(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLInputElement && ['checkbox', 'radio', 'range'].includes(target.type)) event.preventDefault();
}

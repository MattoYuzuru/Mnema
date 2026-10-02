/**
 * True only when the test environment computes real layout (a browser). jsdom reports every box as 0x0, so specs whose
 * assertions are about geometry cannot be evaluated there; those are covered by the real-browser harness
 * (`scripts/browser-identity/run.py`) and are skipped, visibly, instead of passing vacuously.
 */
export const HAS_LAYOUT_ENGINE: boolean = (() => {
    const probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;width:10px;height:10px';
    document.body.appendChild(probe);
    const laidOut = probe.getBoundingClientRect().width > 0;
    probe.remove();
    return laidOut;
})();

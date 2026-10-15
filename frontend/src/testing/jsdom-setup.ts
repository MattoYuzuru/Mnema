/**
 * Test-only shims for browser APIs that jsdom does not implement. Each shim is a minimal, behaviour-neutral
 * stand-in so that specs which assert on component logic can run without a real browser. jsdom has no layout engine
 * (every box is 0x0), so specs here make no geometry assertions: reflow, containment, grid stacking and decoration
 * placement are checked by the real-browser harness scenarios in `scripts/browser-identity/mechanics.mjs`
 * (`mechanics_editor_reflow`, `mechanics_renderer_reflow`, `mechanics_constellation_geometry`,
 * `mechanics_hold_to_delete_geometry` and the `mechanics-study-*-390` Study checks).
 */

function define(target: object, name: string, value: unknown): void {
    if (typeof (target as Record<string, unknown>)[name] === 'undefined') {
        Object.defineProperty(target, name, { value, configurable: true, writable: true });
    }
}

define(Element.prototype, 'scrollIntoView', function scrollIntoView(): void {});

define(HTMLDialogElement.prototype, 'showModal', function showModal(this: HTMLDialogElement): void {
    this.setAttribute('open', '');
});
define(HTMLDialogElement.prototype, 'show', function show(this: HTMLDialogElement): void {
    this.setAttribute('open', '');
});
define(HTMLDialogElement.prototype, 'close', function close(this: HTMLDialogElement, returnValue?: string): void {
    if (returnValue !== undefined) {
        this.returnValue = returnValue;
    }
    if (this.hasAttribute('open')) {
        this.removeAttribute('open');
        this.dispatchEvent(new Event('close'));
    }
});

define(document, 'elementFromPoint', function elementFromPoint(): Element | null {
    return null;
});

class TestMediaStream {
    getTracks(): MediaStreamTrack[] { return []; }
    getAudioTracks(): MediaStreamTrack[] { return []; }
    getVideoTracks(): MediaStreamTrack[] { return []; }
}
define(globalThis, 'MediaStream', TestMediaStream);

// A secure-context browser always exposes `mediaDevices`; the stub denies access until a spec replaces it.
define(navigator, 'mediaDevices', {
    getUserMedia: (): Promise<MediaStream> => Promise.reject(new DOMException('denied by test shim', 'NotAllowedError'))
});

class TestDragEvent extends MouseEvent {
    readonly dataTransfer: DataTransfer | null;

    constructor(type: string, init: MouseEventInit & { dataTransfer?: DataTransfer | null } = {}) {
        super(type, init);
        this.dataTransfer = init.dataTransfer ?? null;
    }
}
define(globalThis, 'DragEvent', TestDragEvent);

// `@defer (on viewport)` and lazy lists observe visibility. jsdom has no layout, so nothing ever intersects: specs that
// need a deferred block render it explicitly (`fixture.getDeferBlocks()`) or replace this class with a controllable one.
class TestIntersectionObserver {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
    takeRecords(): IntersectionObserverEntry[] { return []; }
}
define(globalThis, 'IntersectionObserver', TestIntersectionObserver);

// jsdom has no media-query engine; nothing matches, which is the desktop/no-preference default.
// `document.defaultView` is jsdom's own window object and is not always the same object as `globalThis`.
const noMatchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false
});
define(window, 'matchMedia', noMatchMedia);
if (document.defaultView !== null) {
    define(document.defaultView, 'matchMedia', noMatchMedia);
}

// Chrome does not deliver a nested form's `submit` event to the outer form's listeners (the exercise authoring page
// embeds the learner preview form inside its own form); jsdom bubbles it per the generic event path. Reproduce the
// browser behaviour so a preview submission cannot trigger the outer form's save handler in specs.
// The builder runs every spec file in one worker, so install the listener only once.
const nestedFormGuard = Symbol.for('mnema.nestedFormSubmitGuard');
if (!(nestedFormGuard in document)) {
    Object.defineProperty(document, nestedFormGuard, { value: true });
    document.addEventListener('submit', event => {
        const form = event.target;
        if (form instanceof HTMLFormElement && form.parentElement?.closest('form')) {
            form.addEventListener('submit', nested => nested.stopPropagation(), { once: true });
        }
    }, true);
}

class TestMediaRecorder extends EventTarget {
    // Chrome records WebM/Opus; the recorder component probes this list in order.
    static isTypeSupported(type: string): boolean { return type.startsWith('audio/webm'); }
    start(): void {}
    stop(): void {}
}
define(globalThis, 'MediaRecorder', TestMediaRecorder);

class TestDataTransfer {
    dropEffect: DataTransfer['dropEffect'] = 'none';
    effectAllowed: DataTransfer['effectAllowed'] = 'uninitialized';
    readonly files = [] as unknown as FileList;
    readonly items = [] as unknown as DataTransferItemList;
    private readonly store = new Map<string, string>();
    get types(): readonly string[] { return [...this.store.keys()]; }
    setData(format: string, data: string): void { this.store.set(format, data); }
    getData(format: string): string { return this.store.get(format) ?? ''; }
    clearData(format?: string): void { if (format === undefined) this.store.clear(); else this.store.delete(format); }
    setDragImage(): void {}
}
define(globalThis, 'DataTransfer', TestDataTransfer);

// SVG text metrics need a layout engine; fixed boxes let diagram renderers (Mermaid) run to completion.
define(SVGElement.prototype, 'getBBox', function getBBox(): DOMRect {
    return { x: 0, y: 0, width: 100, height: 20, top: 0, right: 100, bottom: 20, left: 0, toJSON: () => ({}) } as DOMRect;
});
define(SVGElement.prototype, 'getComputedTextLength', function getComputedTextLength(): number { return 100; });

// jsdom does not implement media playback (its methods only log "not implemented"). Specs that care about playback
// spy on these methods; everything else gets a quiet no-op.
Object.assign(HTMLMediaElement.prototype, {
    load(): void {},
    pause(): void {},
    play(): Promise<void> { return Promise.resolve(); }
});

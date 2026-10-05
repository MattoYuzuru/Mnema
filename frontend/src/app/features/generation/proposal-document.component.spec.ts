import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { NativeDocument } from '../../content/native-document';
import { MediaPlaybackApi } from '../../content/rendering/media-playback.api';
import { documentOf, nativeNode } from '../../content/rendering/native-renderer.fixtures';
import { CAPABILITIES_UNAVAILABLE, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ProposalDocumentComponent } from './proposal-document.component';
import { ArtifactDetail, ArtifactSummary, SessionState, parseArtifactDetail, parseArtifactSummary } from './generation.models';
import { artifactWith, clone, examples, ids } from './generation-test-data';
import { EditMemo, EditOutcome, WorkshopSessionStore } from './workshop-session.store';

const newId = (n: number): string => `10000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const BEFORE_REVISION = ids.revision;
const AFTER_REVISION = '4e700000-0000-4000-8000-000000000002';
const ASSET = '31901995-16ea-4f8b-8301-5d8e03004c72';
const paragraph = (n: number, text: string) => nativeNode('paragraph', {}, [nativeNode('text', { text, marks: [] })], { id: newId(n) });
const sample = (second = 'Второй абзац про частицы.'): NativeDocument => documentOf([
    nativeNode('heading', { level: 2 }, [nativeNode('text', { text: 'Глагол', marks: [] })], { id: newId(1) }),
    paragraph(2, 'Первый абзац про глаголы.'),
    paragraph(3, second),
    nativeNode('image', { assetId: ASSET, alt: 'схема глаголов' }, [], { id: newId(4) }),
    nativeNode('audio', { assetId: '948ef76d-68ab-4a79-9f83-f3a45ffb3eda', title: 'Произношение' }, [], { id: newId(5) }),
    paragraph(6, 'Последний абзац.')
]);
const turnOf = (change: Record<string, unknown> = {}) => ({ ...clone(examples['turnApplied']), turnId: '7a7a0000-0000-4000-8000-0000000000aa', preset: 'SIMPLER',
    action: 'REWRITE', instruction: null, targetNodeIds: [newId(2)], resultRevisionId: AFTER_REVISION, status: 'APPLIED', ...change });
const revisions = (...list: [string, string][]) => list.map(([revisionId, cause]) => ({ revisionId, cause, createdAt: '2026-10-02T09:00:42Z' }));

function fakeStore() {
    return { edits: signal<Readonly<Record<string, EditMemo>>>({}), closedTurns: signal<ReadonlySet<string>>(new Set()), busy: signal<ReadonlySet<string>>(new Set()),
        edit: vi.fn<(...args: unknown[]) => Promise<EditOutcome>>(), revert: vi.fn<(...args: unknown[]) => Promise<boolean>>(),
        dismissEdit: vi.fn(), loadRevision: vi.fn<(...args: unknown[]) => Promise<NativeDocument | null>>(),
        editCost: vi.fn<(...args: unknown[]) => Promise<{ text: string; canStart: boolean; blocked: string | null } | null>>(), notify: vi.fn(),
        selectCandidate: vi.fn<(...args: unknown[]) => Promise<{ ok: true } | { ok: false; message: string }>>() };
}

describe('ProposalDocumentComponent', () => {
    let fixture: ComponentFixture<ProposalDocumentComponent>;
    let store: ReturnType<typeof fakeStore>;
    let host: HTMLElement;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const blocks = (): HTMLElement[] => [...root().querySelectorAll<HTMLElement>('.native-document > [data-node-id]')];
    const block = (n: number): HTMLElement => root().querySelector<HTMLElement>(`[data-node-id="${newId(n)}"]`)!;
    const labelled = (label: string): HTMLButtonElement | undefined => [...root().querySelectorAll<HTMLButtonElement>('button')]
        .find(button => button.textContent!.trim() === label);
    const summary = (state = 'PROPOSED', overrides: Record<string, unknown> = {}): ArtifactSummary => parseArtifactSummary(artifactWith(ids.first, 0, state,
        { currentRevisionId: BEFORE_REVISION, ...overrides }));
    const detailOf = (document: NativeDocument, extra: Record<string, unknown> = {}, revision = BEFORE_REVISION): ArtifactDetail => parseArtifactDetail({
        ...clone(examples['artifactDetailItem']), currentRevisionId: revision, revision: { ...clone(examples['artifactDetailItem']).revision, revisionId: revision,
            payload: { kind: 'NATIVE_DOCUMENT', document } }, turns: [], revisions: revisions([BEFORE_REVISION, 'INITIAL']), ...extra });
    const capabilities = (change: Partial<LearningCapabilities> = {}): LearningCapabilities => ({ ...CAPABILITIES_UNAVAILABLE, ...change });

    async function create(inputs: Record<string, unknown> = {}, doc = sample()): Promise<void> {
        TestBed.resetTestingModule();
        store = fakeStore();
        store.editCost.mockResolvedValue({ text: '≈ 0,3 % лимита', canStart: true, blocked: null });
        store.edit.mockResolvedValue({ ok: true, turn: parseTurn() });
        store.loadRevision.mockResolvedValue(sample());
        TestBed.configureTestingModule({ providers: [{ provide: WorkshopSessionStore, useValue: store },
            { provide: MediaPlaybackApi, useValue: { read: () => new Promise(() => {}) } }] });
        fixture = TestBed.createComponent(ProposalDocumentComponent);
        fixture.componentRef.setInput('artifact', summary());
        fixture.componentRef.setInput('document', doc);
        fixture.componentRef.setInput('detail', detailOf(doc));
        fixture.componentRef.setInput('sessionState', 'REVIEW' as SessionState);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        await fixture.whenStable();
        host = root().querySelector<HTMLElement>('.document-host')!;
        await settle();
    }
    const parseTurn = () => ({ turnId: 't', status: 'QUEUED', action: 'REWRITE', preset: 'SIMPLER', instruction: null, targetNodeIds: [], resultRevisionId: null,
        errorCode: null, createdAt: '2026-10-02T09:00:42Z' }) as never;
    async function settle(): Promise<void> {
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    }
    const textNode = (element: HTMLElement): Text => {
        const walker = window.document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        let node = walker.nextNode();
        while (node !== null && (node.textContent ?? '').trim() === '') node = walker.nextNode();
        return node as Text;
    };
    const select = (from: number, to = from): void => {
        const range = window.document.createRange();
        range.setStart(textNode(block(from)), 3);
        range.setEnd(textNode(block(to)), Math.min(8, textNode(block(to)).length));
        const selection = window.document.getSelection()!;
        selection.removeAllRanges();
        selection.addRange(range);
    };

    beforeEach(() => {
        vi.spyOn(window.document, 'getSelection');
        Object.defineProperty(HTMLElement.prototype, 'showPopover', { value: vi.fn(), configurable: true, writable: true });
    });
    afterEach(() => {
        window.document.getSelection()?.removeAllRanges();
        delete (HTMLElement.prototype as { showPopover?: unknown }).showPopover;
        vi.useRealTimers();
    });

    describe('what it draws', () => {
        it('draws the material with node ids on the top-level blocks only, and nothing else about editing while nothing is selected', async () => {
            await create();
            expect(blocks().map(node => node.dataset['nodeId'])).toEqual([1, 2, 3, 4, 5, 6].map(newId));
            expect(root().querySelectorAll('[data-node-id]')).toHaveLength(6);
            expect(root().querySelector('.selection-actions, .selection-bar, app-ai-prompt-window')).toBeNull();
            expect(root().querySelector('.edit-history')).toBeNull();
            expect(blocks().some(node => node.hasAttribute('aria-busy'))).toBe(false);
        });

        it('marks the blocks of a running rewrite as busy and says so under the last one, while the text stays readable', async () => {
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'RUNNING', resultRevisionId: null, targetNodeIds: [newId(2), newId(3)] })] }),
                artifact: summary('REVISING') });
            expect([2, 3].map(n => block(n).getAttribute('aria-busy'))).toEqual(['true', 'true']);
            expect(block(1).hasAttribute('aria-busy')).toBe(false);
            expect(block(2).classList.contains('is-rewriting')).toBe(true);
            expect(block(2).textContent).toBe('Первый абзац про глаголы.');
            const captions = root().querySelectorAll('.rewriting-caption');
            expect(captions).toHaveLength(1);
            expect(captions[0]!.textContent).toBe('Мнема переписывает…');
            expect(block(3).nextElementSibling).toBe(captions[0]);
        });

        it('does not mark a turn that removes media (it is applied at once and has no run)', async () => {
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'RUNNING', action: 'REMOVE_MEDIA', targetNodeIds: [newId(4)] })] }) });
            expect(blocks().some(node => node.hasAttribute('aria-busy'))).toBe(false);
        });
    });

    describe('a selection', () => {
        it('offers «Попросить Мнему…» in a group under it, widened to the whole block, and nothing for a collapsed selection', async () => {
            await create();
            fixture.componentInstance.refresh();
            await settle();
            expect(root().querySelector('.selection-actions')).toBeNull();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            const group = root().querySelector<HTMLElement>('.selection-actions')!;
            expect(group.getAttribute('popover')).toBe('manual');
            expect(group.getAttribute('role')).toBe('group');
            expect(group.getAttribute('aria-label')).toBe('Действия с выделенным текстом');
            expect(group.querySelector('button')!.textContent!.trim()).toBe('Попросить Мнему…');
            expect(group.querySelector('button')!.getAttribute('aria-keyshortcuts')).toBe('Shift+F10');
            expect(group.querySelector('.group-key')!.textContent).toBe('Shift+F10');
            expect(root().querySelector('.selection-bar')).toBeNull();
            window.document.getSelection()!.collapseToStart();
            fixture.componentInstance.refresh();
            await settle();
            expect(root().querySelector('.selection-actions')).toBeNull();
        });

        it('follows the browser: selectionchange makes the group appear after a frame', async () => {
            vi.useFakeTimers();
            await create();
            select(3);
            window.document.dispatchEvent(new Event('selectionchange'));
            window.document.dispatchEvent(new Event('selectionchange'));
            await vi.advanceTimersByTimeAsync(40);
            await settle();
            expect(root().querySelector('.selection-actions')).not.toBeNull();
        });

        it('offers nothing where a rewrite is not possible: media only, outside the material, a cancelled session, an old revision, an exercise', async () => {
            await create();
            select(4, 5);
            fixture.componentInstance.refresh();
            await settle();
            expect(root().querySelector('.selection-actions')).toBeNull();
            const outside = window.document.createElement('p');
            outside.textContent = 'снаружи';
            window.document.body.appendChild(outside);
            const range = window.document.createRange();
            range.selectNodeContents(outside);
            window.document.getSelection()!.removeAllRanges();
            window.document.getSelection()!.addRange(range);
            fixture.componentInstance.refresh();
            await settle();
            expect(root().querySelector('.selection-actions')).toBeNull();
            outside.remove();
            for (const change of [{ sessionState: 'CANCELLED' },  { detail: detailOf(sample(), {}, AFTER_REVISION) },
                { artifact: parseArtifactSummary({ ...artifactWith(ids.first, 0, 'PROPOSED', { currentRevisionId: BEFORE_REVISION }), targetKind: 'EXERCISE' }) }]) {
                await create(change);
                select(2);
                fixture.componentInstance.refresh();
                await settle();
                expect(root().querySelector('.selection-actions'), JSON.stringify(Object.keys(change))).toBeNull();
            }
        });

        it('counts a block that only the edge of the selection reaches out of the run, and includes the media between two paragraphs', async () => {
            await create();
            select(3, 6);
            fixture.componentInstance.refresh();
            await settle();
            labelled('Попросить Мнему…')!.click();
            await settle();
            await (fixture.componentInstance as never as { send(ask: unknown): Promise<void> }).send({ preset: 'SIMPLER', instruction: null });
            expect(store.edit).toHaveBeenCalledWith(ids.first, expect.objectContaining({ action: 'REWRITE', nodeIds: [3, 4, 5, 6].map(newId), anchorBefore: newId(2), anchorAfter: null }),
                expect.any(AbortSignal));
        });
    });

    describe('a group that cannot open the window', () => {
        it('is drawn disabled with its reason while a rewrite is running or another command is in flight, and the menu key does not open the window', async () => {
            for (const [change, reason] of [[{ artifact: summary('REVISING') }, 'Мнема ещё переписывает этот материал'],
                [{ busy: true }, 'Подождите: предыдущее действие ещё выполняется']] as const) {
                await create(change);
                select(2);
                fixture.componentInstance.refresh();
                await settle();
                const button = root().querySelector<HTMLButtonElement>('.selection-actions button')!;
                expect(button.getAttribute('aria-disabled')).toBe('true');
                expect(root().querySelector('.group-note')!.textContent).toBe(reason);
                expect(button.getAttribute('aria-describedby')).toBe(root().querySelector('.group-note')!.id);
                expect(root().querySelector('.group-key')).toBeNull();
                button.click();
                window.document.dispatchEvent(new KeyboardEvent('keydown', { key: 'F10', shiftKey: true, bubbles: true, cancelable: true }));
                await settle();
                expect(root().querySelector('app-ai-prompt-window')).toBeNull();
            }
        });

        it('stays for the rewrite that has just started, instead of vanishing under the user', async () => {
            await create();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            fixture.componentRef.setInput('artifact', summary('REVISING'));
            await settle();
            expect(root().querySelector('.selection-actions button')!.getAttribute('aria-disabled')).toBe('true');
        });
    });

    describe('the keyboard menu', () => {
        const press = (type: string, init: KeyboardEventInit): KeyboardEvent => {
            const event = new KeyboardEvent(type, { bubbles: true, cancelable: true, ...init });
            window.document.dispatchEvent(event);
            return event;
        };

        it('takes the release of the key whose press opened the window, and nothing else: the menu key keeps its meaning in the field', async () => {
            await create();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            expect(press('keydown', { key: 'F10', shiftKey: true }).defaultPrevented).toBe(true);
            await settle();
            expect(root().querySelector('app-ai-prompt-window')).not.toBeNull();
            expect(press('keyup', { key: 'F10', shiftKey: true }).defaultPrevented).toBe(true);
            // Pressed in the open window, the same key is the browser's.
            expect(press('keydown', { key: 'F10', shiftKey: true }).defaultPrevented).toBe(false);
            expect(press('keyup', { key: 'F10', shiftKey: true }).defaultPrevented).toBe(false);
            expect(press('keyup', { key: 'a' }).defaultPrevented).toBe(false);
        });

        it('opens the window for a context menu the keyboard asked for, and leaves the mouse\'s menu native', async () => {
            await create();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            const mouse = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 });
            window.document.dispatchEvent(mouse);
            expect(mouse.defaultPrevented).toBe(false);
            await settle();
            expect(root().querySelector('app-ai-prompt-window')).toBeNull();
            const keyboard = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: -1 });
            window.document.dispatchEvent(keyboard);
            expect(keyboard.defaultPrevented).toBe(true);
            await settle();
            expect(root().querySelector('app-ai-prompt-window')).not.toBeNull();
            // With no group (the window is open, or nothing is selected) the menu is the browser's.
            const again = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: -1 });
            window.document.dispatchEvent(again);
            expect(again.defaultPrevented).toBe(false);
        });

        it('says where the group goes: clamped to the screen, and above the selection when it is at the bottom', async () => {
            vi.stubGlobal('innerWidth', 1000);
            vi.stubGlobal('innerHeight', 800);
            await create();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            const instance = fixture.componentInstance as unknown as { anchor: { set(value: unknown): void } };
            instance.anchor.set({ top: 760, bottom: 780, left: 980, right: 995 });
            await settle();
            const style = root().querySelector<HTMLElement>('.selection-actions')!.style;
            expect([style.top, style.left]).toEqual(['auto', '692px']);
            expect(style.bottom).toBe('46px');
        });
    });

    describe('the budget', () => {
        it('shows why an edit does not fit, and pressing explains it instead of sending a request that would fail', async () => {
            await create();
            store.editCost.mockResolvedValue({ text: '≈ 40 % лимита', canStart: false, blocked: 'На сегодня лимит ИИ исчерпан.' });
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            labelled('Попросить Мнему…')!.click();
            await settle();
            await settle();
            const win = root().querySelector('app-ai-prompt-window')!;
            expect(win.querySelector('.window-cost')!.textContent).toBe('≈ 40 % лимита · не хватит лимита');
            [...win.querySelectorAll<HTMLButtonElement>('.chip')].find(chip => chip.textContent!.trim() === 'Проще')!.click();
            await settle();
            expect(store.edit).not.toHaveBeenCalled();
            expect(win.querySelector('.window-error')!.textContent).toBe('На сегодня лимит ИИ исчерпан.');
            expect(win.querySelector('.window-button.primary')!.getAttribute('aria-disabled')).toBeNull();
        });
    });

    describe('after a reload', () => {
        it('draws the strip of a failed turn from the turns alone, in words, until the user closes it', async () => {
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'FAILED', errorCode: 'PROVIDER_UNAVAILABLE', resultRevisionId: null })] }) });
            const strip = root().querySelector('.rewrite-strip')!;
            expect(strip.classList.contains('is-failed')).toBe(true);
            expect(strip.textContent).toContain('Сервис ИИ временно недоступен. Текст не изменился, лимит не списан.');
            expect(block(2).nextElementSibling).toBe(strip);
            labelled('Ещё раз')!.click();
            await settle();
            expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'REWRITE', nodeIds: [newId(2)], anchorBefore: newId(1), anchorAfter: newId(3), preset: 'SIMPLER',
                instruction: null, againOf: turnOf()['turnId'] });
            labelled('Закрыть')!.click();
            expect(store.dismissEdit).toHaveBeenCalledWith(ids.first, turnOf()['turnId']);
            store.closedTurns.set(new Set([turnOf()['turnId'] as string]));
            await settle();
            expect(root().querySelector('.rewrite-strip')).toBeNull();
        });

        it('draws no strip for an applied rewrite it only read (it does not know the revision it started from), but lists it in the history', async () => {
            await create({ artifact: summary('PROPOSED', { currentRevisionId: AFTER_REVISION }),
                detail: detailOf(sample(), { turns: [turnOf()], revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'EDIT']) }, AFTER_REVISION) });
            expect(root().querySelector('.rewrite-strip')).toBeNull();
            expect(root().querySelectorAll('.history-entry')).toHaveLength(2);
        });

        it('draws no strip for a failed turn that is not the last one, a media redo, or one whose blocks are gone', async () => {
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'FAILED', errorCode: 'REFUSAL', resultRevisionId: null }), turnOf({ turnId: '7a7a0000-0000-4000-8000-0000000000bb' })] }) });
            expect(root().querySelector('.rewrite-strip')).toBeNull();
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'FAILED', action: 'IMAGE_SEARCH', resultRevisionId: null })] }) });
            expect(root().querySelector('.rewrite-strip')).toBeNull();
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'FAILED', resultRevisionId: null, targetNodeIds: [newId(99)] })] }) });
            expect(root().querySelector('.rewrite-strip')).toBeNull();
        });
    });

    describe('the window', () => {
        async function open(first = 2, last = first): Promise<void> {
            select(first, last);
            fixture.componentInstance.refresh();
            await settle();
            labelled('Попросить Мнему…')!.click();
            await settle();
        }
        const win = (): HTMLElement | null => root().querySelector('app-ai-prompt-window');
        const ask = (label: string): void => { labelled(label)!.click(); };

        it('opens from the group with the quote and the cost line, and keeps the chosen block painted (class fallback without the Highlight API)', async () => {
            await create();
            await open();
            expect(win()).not.toBeNull();
            expect(win()!.querySelector('.window-quote')!.textContent).toContain('Выделено: «');
            expect(store.editCost).toHaveBeenCalledWith(ids.first, 1);
            await settle();
            expect(win()!.querySelector('.window-cost')!.textContent).toBe('≈ 0,3 % лимита');
            expect(block(2).classList.contains('is-target')).toBe(true);
            expect(root().querySelector('.selection-actions')).toBeNull();
        });

        it('paints with the Highlight API where there is one, and takes the paint away when it closes', async () => {
            const registry = new Map<string, unknown>();
            vi.stubGlobal('Highlight', class { constructor(public readonly range: Range) {} });
            vi.stubGlobal('CSS', { highlights: registry });
            await create();
            await open();
            expect(registry.has('mnema-ai-target')).toBe(true);
            expect(block(2).classList.contains('is-target')).toBe(false);
            win()!.querySelector<HTMLButtonElement>('.window-close')!.click();
            await settle();
            expect(registry.has('mnema-ai-target')).toBe(false);
        });

        it('sends a preset as a rewrite of the run with its anchors, closes, clears the selection and returns focus to the document', async () => {
            await create();
            await open(2);
            ask('Проще');
            await settle();
            expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'REWRITE', nodeIds: [newId(2)], anchorBefore: newId(1), anchorAfter: newId(3), preset: 'SIMPLER', instruction: null },
                expect.any(AbortSignal));
            expect(win()).toBeNull();
            await settle();
            expect(window.document.getSelection()!.rangeCount).toBe(0);
            expect(window.document.activeElement).toBe(host);
        });

        it('sends a typed request as a free edit', async () => {
            await create();
            await open(2);
            const field = win()!.querySelector('textarea')!;
            field.value = 'объясни как ребёнку';
            field.dispatchEvent(new Event('input'));
            field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
            await settle();
            expect(store.edit.mock.calls[0]![1]).toMatchObject({ action: 'FREE', preset: null, instruction: 'объясни как ребёнку' });
        });

        it('explains a refusal in the window, which stays open for another try, and a stale proposal does not close it', async () => {
            await create();
            await open(2);
            store.edit.mockResolvedValue({ ok: false, aborted: false, message: 'Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.' });
            ask('Короче');
            await settle();
            expect(win()!.querySelector('.window-error')!.textContent).toBe('Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.');
            // The artifact turns REVISING (the other edit was heard of): the explanation stays.
            fixture.componentRef.setInput('artifact', summary('REVISING'));
            await settle();
            expect(win()).not.toBeNull();
            store.edit.mockResolvedValue({ ok: false, aborted: true, message: '' });
            ask('Пример');
            await settle();
            expect(win()).not.toBeNull();
        });

        it('takes an unsent request back with «Отменить»: the signal is aborted and the window stays', async () => {
            await create();
            await open(2);
            let signal: AbortSignal | undefined;
            store.edit.mockImplementation((_id, _ask, abort) => new Promise<EditOutcome>(resolve => {
                signal = abort as AbortSignal;
                (abort as AbortSignal).addEventListener('abort', () => resolve({ ok: false, aborted: true, message: '' }));
            }));
            ask('Проще');
            await settle();
            expect(win()!.querySelector<HTMLButtonElement>('.window-button.primary')!.textContent).toContain('Отправляю…');
            labelled('Отменить')!.click();
            await settle();
            expect(signal!.aborted).toBe(true);
            expect(win()).not.toBeNull();
            expect(labelled('Отменить')).toBeUndefined();
        });

        it('closes on Esc with focus back on the document and the selection put back, keeping the typed request for the same selection', async () => {
            await create();
            await open(2);
            const field = win()!.querySelector('textarea')!;
            field.value = 'иначе';
            field.dispatchEvent(new Event('input'));
            win()!.querySelector('[popover]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            await settle();
            expect(win()).toBeNull();
            expect(window.document.activeElement).toBe(host);
            expect(window.document.getSelection()!.rangeCount).toBe(1);
            expect(window.document.getSelection()!.toString()).not.toBe('');
            fixture.componentInstance.refresh();
            await settle();
            labelled('Попросить Мнему…')!.click();
            await settle();
            expect(win()!.querySelector('textarea')!.value).toBe('иначе');
            win()!.querySelector<HTMLButtonElement>('.window-close')!.click();
            await settle();
            select(3);
            fixture.componentInstance.refresh();
            await settle();
            labelled('Попросить Мнему…')!.click();
            await settle();
            expect(win()!.querySelector('textarea')!.value).toBe('');
        });

        it('closes on a click outside, and leaves focus where the click put it, or on the document when it fell on nothing', async () => {
            vi.useFakeTimers();
            await create();
            await open(2);
            win()!.querySelector('textarea')!.dispatchEvent(new Event('pointerdown', { bubbles: true }));
            expect(win()).not.toBeNull();
            window.document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }));
            await vi.advanceTimersByTimeAsync(1);
            await settle();
            expect(win()).toBeNull();
            expect(window.document.activeElement).toBe(host);
        });

        it('opens from the keyboard with Shift+F10 or the menu key, but only while there is a selection', async () => {
            await create();
            const press = (init: KeyboardEventInit) => window.document.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init }));
            press({ key: 'F10', shiftKey: true });
            await settle();
            expect(win()).toBeNull();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            press({ key: 'F10' });
            await settle();
            expect(win()).toBeNull();
            press({ key: 'F10', shiftKey: true });
            await settle();
            expect(win()).not.toBeNull();
            win()!.querySelector<HTMLButtonElement>('.window-close')!.click();
            await settle();
            fixture.componentInstance.refresh();
            await settle();
            press({ key: 'ContextMenu' });
            await settle();
            expect(win()).not.toBeNull();
        });

        it('says nothing in the window when the cost cannot be read', async () => {
            await create();
            store.editCost.mockResolvedValue(null);
            await open(2);
            await settle();
            expect(win()!.querySelector('.window-cost')).toBeNull();
        });
    });

    describe('on a touch screen', () => {
        beforeEach(() => {
            vi.spyOn(window, 'matchMedia').mockImplementation(query => ({ matches: query === '(pointer: coarse)', media: query, addEventListener: () => undefined,
                removeEventListener: () => undefined }) as unknown as MediaQueryList);
        });

        it('draws a bar at the bottom instead of a floating group, and the bar opens a bottom sheet with the same content', async () => {
            await create();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            expect(root().querySelector('.selection-actions')).toBeNull();
            const bar = root().querySelector<HTMLElement>('.selection-bar')!;
            expect(bar.textContent!.trim()).toBe('Попросить Мнему…');
            const press = new Event('mousedown', { bubbles: true, cancelable: true });
            bar.dispatchEvent(press);
            expect(press.defaultPrevented).toBe(true);
            bar.querySelector('button')!.click();
            await settle();
            expect(root().querySelector('app-ai-prompt-window dialog')).not.toBeNull();
            expect(root().querySelector('app-ai-prompt-window [popover]')).toBeNull();
            expect(root().querySelector('.selection-bar')).toBeNull();
            expect(root().querySelector('app-ai-prompt-window textarea')!.getAttribute('enterkeyhint')).toBe('send');
        });

        it('keeps the bar for the click that follows a press on it, even if the selection went with the press', async () => {
            await create();
            select(2);
            fixture.componentInstance.refresh();
            await settle();
            root().querySelector('.selection-bar')!.dispatchEvent(new Event('pointerdown', { bubbles: true }));
            window.document.getSelection()!.removeAllRanges();
            fixture.componentInstance.refresh();
            await settle();
            expect(root().querySelector('.selection-bar')).not.toBeNull();
        });
    });

    describe('the strip under a rewritten range', () => {
        const memo = (change: Partial<EditMemo> = {}): EditMemo => ({ turnId: turnOf()['turnId'] as string, baseRevisionId: BEFORE_REVISION, dismissed: false, announced: true,
            ask: { action: 'REWRITE', nodeIds: [newId(2)], anchorBefore: newId(1), anchorAfter: newId(3), preset: 'SIMPLER' }, ...change });
        const applied = (extra: Record<string, unknown> = {}) => ({ artifact: summary('PROPOSED', { currentRevisionId: AFTER_REVISION }),
            detail: detailOf(sample(), { turns: [turnOf()], revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'EDIT']) }, AFTER_REVISION), ...extra });
        const strip = (): HTMLElement | null => root().querySelector('.rewrite-strip');

        it('says «Переписано» with its four actions right under the last block of the range', async () => {
            await create(applied());
            store.edits.set({ [ids.first]: memo() });
            await settle();
            expect(strip()!.querySelector('.strip-state')!.textContent).toBe('Переписано');
            expect([...strip()!.querySelectorAll('button')].map(button => button.textContent!.trim())).toEqual(['Показать изменения', 'Оставить', 'Вернуть', 'Ещё раз']);
            expect(block(2).nextElementSibling).toBe(strip());
            expect(strip()!.getAttribute('role')).toBe('group');
        });

        it('has no strip for a turn of an earlier page, a closed strip, another revision or a rewrite still running', async () => {
            await create(applied());
            expect(strip()).toBeNull();
            store.edits.set({ [ids.first]: memo({ dismissed: true }) });
            await settle();
            expect(strip()).toBeNull();
            store.edits.set({ [ids.first]: memo({ turnId: 'other' }) });
            await settle();
            expect(strip()).toBeNull();
            store.edits.set({ [ids.first]: memo() });
            fixture.componentRef.setInput('detail', detailOf(sample(), { turns: [turnOf({ resultRevisionId: BEFORE_REVISION })] }, AFTER_REVISION));
            await settle();
            expect(strip()).toBeNull();
            fixture.componentRef.setInput('detail', detailOf(sample(), { turns: [turnOf({ status: 'RUNNING', resultRevisionId: null })] }, AFTER_REVISION));
            await settle();
            expect(strip()).toBeNull();
        });

        it('finds the range again between its anchors when the rewrite gave the block new ids, and falls back to the blocks it asked for', async () => {
            const grown = documentOf([
                nativeNode('heading', { level: 2 }, [nativeNode('text', { text: 'Глагол', marks: [] })], { id: newId(1) }),
                paragraph(20, 'Новый один.'), paragraph(21, 'Новый два.'), paragraph(3, 'Второй абзац про частицы.')]);
            await create({ artifact: summary('PROPOSED', { currentRevisionId: AFTER_REVISION }),
                detail: detailOf(grown, { turns: [turnOf()], revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'EDIT']) }, AFTER_REVISION) }, grown);
            store.edits.set({ [ids.first]: memo() });
            await settle();
            expect(blocks().map(node => node.dataset['nodeId'])).toEqual([1, 20, 21, 3].map(newId));
            expect(block(21).nextElementSibling).toBe(strip());
            store.edits.set({ [ids.first]: memo({ ask: { ...memo().ask, anchorBefore: 'gone', anchorAfter: 'gone', nodeIds: [newId(3)] } }) });
            await settle();
            expect(block(3).nextElementSibling).toBe(strip());
        });

        it('tells why a rewrite failed and that nothing changed, and offers «Ещё раз» and «Закрыть»', async () => {
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'FAILED', errorCode: 'REFUSAL', resultRevisionId: null })] }) });
            store.edits.set({ [ids.first]: memo() });
            await settle();
            expect(strip()!.classList.contains('is-failed')).toBe(true);
            expect(strip()!.textContent).toContain('Мнема отказалась переписывать этот фрагмент. Текст не изменился, лимит не списан.');
            expect([...strip()!.querySelectorAll('button')].map(button => button.textContent!.trim())).toEqual(['Ещё раз', 'Закрыть']);
            labelled('Закрыть')!.click();
            expect(store.dismissEdit).toHaveBeenCalledWith(ids.first, '7a7a0000-0000-4000-8000-0000000000aa');
            await create({ detail: detailOf(sample(), { turns: [turnOf({ status: 'CANCELLED', resultRevisionId: null })] }) });
            store.edits.set({ [ids.first]: memo() });
            await settle();
            expect(strip()!.textContent).toContain('Правка остановлена.');
        });

        it('«Оставить» closes the strip and puts focus on the document', async () => {
            await create(applied());
            store.edits.set({ [ids.first]: memo() });
            await settle();
            labelled('Оставить')!.click();
            await settle();
            expect(store.dismissEdit).toHaveBeenCalledWith(ids.first, '7a7a0000-0000-4000-8000-0000000000aa');
            expect(window.document.activeElement).toBe(host);
        });

        it('«Вернуть» goes back to the revision the rewrite started from, only when that revision can be restored', async () => {
            await create(applied());
            store.edits.set({ [ids.first]: memo() });
            store.revert.mockResolvedValue(true);
            await settle();
            labelled('Вернуть')!.click();
            await settle();
            expect(store.revert).toHaveBeenCalledWith(ids.first, BEFORE_REVISION);
            await create(applied({ detail: detailOf(sample(), { turns: [turnOf()], revisions: revisions([AFTER_REVISION, 'EDIT']) }, AFTER_REVISION) }));
            store.edits.set({ [ids.first]: memo() });
            await settle();
            expect(labelled('Вернуть')).toBeUndefined();
            expect(labelled('Ещё раз')).toBeDefined();
        });

        it('«Ещё раз» asks the same again as a new command on the blocks now there, and says why when it is refused', async () => {
            await create(applied());
            store.edits.set({ [ids.first]: memo() });
            await settle();
            labelled('Ещё раз')!.click();
            await settle();
            expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'REWRITE', anchorBefore: newId(1), anchorAfter: newId(3), preset: 'SIMPLER', instruction: null,
                nodeIds: [newId(2)], againOf: '7a7a0000-0000-4000-8000-0000000000aa' });
            expect(store.notify).not.toHaveBeenCalled();
            store.edit.mockResolvedValue({ ok: false, aborted: false, message: 'Не хватает лимита ИИ на эту правку.' });
            labelled('Ещё раз')!.click();
            await settle();
            expect(store.notify).toHaveBeenCalledWith('Не хватает лимита ИИ на эту правку.');
        });

        it('waits while another command runs: the actions say they are not ready and do nothing', async () => {
            await create(applied({ busy: true }));
            store.edits.set({ [ids.first]: memo() });
            await settle();
            for (const label of ['Вернуть', 'Ещё раз']) expect(labelled(label)!.getAttribute('aria-disabled'), label).toBe('true');
            labelled('Вернуть')!.click();
            labelled('Ещё раз')!.click();
            await settle();
            expect(store.revert).not.toHaveBeenCalled();
            expect(store.edit).not.toHaveBeenCalled();
        });
    });

    describe('the inline diff', () => {
        const memo: EditMemo = { turnId: turnOf()['turnId'] as string, baseRevisionId: BEFORE_REVISION, dismissed: false, announced: true,
            ask: { action: 'REWRITE', nodeIds: [newId(2)], anchorBefore: newId(1), anchorAfter: newId(3), preset: 'SIMPLER' } };
        const after = () => sample().root.content.map(node => node.id === newId(2) ? paragraph(2, 'Первый абзац про глаголы. Переписано: Проще.') : node);
        async function applied(): Promise<void> {
            const rewritten = documentOf(after());
            await create({ artifact: summary('PROPOSED', { currentRevisionId: AFTER_REVISION }),
                detail: detailOf(rewritten, { turns: [turnOf()], revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'EDIT']) }, AFTER_REVISION) }, rewritten);
            store.edits.set({ [ids.first]: memo });
            await settle();
        }

        it('shows the added words as an insertion with a spoken prefix, in place of the rewritten block, and takes it away again', async () => {
            await applied();
            labelled('Показать изменения')!.click();
            await settle();
            await settle();
            const diff = root().querySelector('.rewrite-diff')!;
            expect(diff.querySelectorAll('ins')).toHaveLength(1);
            expect(diff.querySelector('ins')!.textContent).toBe('добавлено: Переписано: Проще.');
            expect(diff.querySelector('ins .sr-only')!.textContent).toBe('добавлено: ');
            expect(diff.querySelector('del')).toBeNull();
            expect(diff.textContent).toContain('Первый абзац про глаголы.');
            expect(root().querySelector(`[data-node-id="${newId(2)}"]`)).toBeNull();
            expect(blocks()).toHaveLength(5);
            const toggle = labelled('Скрыть изменения')!;
            expect(toggle.getAttribute('aria-expanded')).toBe('true');
            expect(toggle.getAttribute('aria-controls')).toBe(diff.id);
            expect(store.loadRevision).toHaveBeenCalledWith(ids.first, BEFORE_REVISION);
            toggle.click();
            await settle();
            expect(root().querySelector('.rewrite-diff')).toBeNull();
            expect(root().querySelector(`[data-node-id="${newId(2)}"]`)).not.toBeNull();
            expect(labelled('Показать изменения')!.getAttribute('aria-expanded')).toBe('false');
        });

        it('shows a deletion too, says nothing changed for the same text, and reports a version it could not read', async () => {
            await applied();
            store.loadRevision.mockResolvedValue(documentOf(sample().root.content.map(node => node.id === newId(2) ? paragraph(2, 'Первый абзац про глаголы. Лишнее слово.') : node)));
            labelled('Показать изменения')!.click();
            await settle();
            await settle();
            expect(root().querySelector('.rewrite-diff del')!.textContent).toBe('удалено: Лишнее слово.');
            labelled('Скрыть изменения')!.click();
            await settle();
            store.loadRevision.mockResolvedValue(documentOf(after()));
            labelled('Показать изменения')!.click();
            await settle();
            await settle();
            expect(root().querySelector('.diff-same')!.textContent).toBe('Текст не изменился.');
            labelled('Скрыть изменения')!.click();
            await settle();
            store.loadRevision.mockResolvedValue(null);
            labelled('Показать изменения')!.click();
            await settle();
            await settle();
            expect(root().querySelector('.strip-note.is-error')!.textContent).toBe('Не удалось загрузить прежнюю версию.');
            expect(root().querySelector('.rewrite-diff')).toBeNull();
            expect(block(2)).not.toBeNull();
        });

        it('starts a new rewrite with its diff closed', async () => {
            await applied();
            labelled('Показать изменения')!.click();
            await settle();
            await settle();
            store.edits.set({ [ids.first]: { ...memo, turnId: 'a-new-turn' } });
            await settle();
            expect(root().querySelector('.rewrite-diff')).toBeNull();
        });
    });

    describe('the history of edits', () => {
        const history = (current: string) => ({ turns: [turnOf(), turnOf({ turnId: '7a7a0000-0000-4000-8000-0000000000bb', status: 'FAILED', errorCode: 'REFUSAL',
            resultRevisionId: null, preset: null, instruction: 'Сделай подробнее', action: 'FREE' }), turnOf({ turnId: '7a7a0000-0000-4000-8000-0000000000cc', action: 'REMOVE_MEDIA',
            preset: null, resultRevisionId: '4e700000-0000-4000-8000-000000000003' })],
            revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'EDIT'], ['4e700000-0000-4000-8000-000000000003', 'MEDIA']), current });

        it('lists the original, every turn with its status and time, marks the shown version and offers the others', async () => {
            const { current, ...extra } = history(AFTER_REVISION);
            await create({ artifact: summary('PROPOSED', { currentRevisionId: current }), detail: detailOf(sample(), extra, current) });
            const details = root().querySelector<HTMLDetailsElement>('.edit-history')!;
            expect(details.querySelector('summary')!.textContent).toBe('История правок (3)');
            const items = [...details.querySelectorAll('li')];
            expect(items.map(item => item.querySelector('.history-ask')!.textContent)).toEqual(['Исходная версия', 'Проще', 'Сделай подробнее', 'Убрано медиа']);
            expect(items[1]!.classList.contains('is-current')).toBe(true);
            expect(items[1]!.textContent).toContain('сейчас показана');
            expect(items[2]!.querySelector('.history-status')!.textContent).toBe('не удалось — Мнема отказалась переписывать этот фрагмент');
            expect(items[0]!.querySelector('button')!.textContent).toBe('Вернуть к этой версии');
            expect(items[2]!.querySelector('button')).toBeNull();
            expect(items[3]!.querySelector('button')).not.toBeNull();
            expect(items[1]!.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-02T09:00:42Z');
        });

        it('restores a version through the store, and refuses while a command runs or when the artifact cannot be reverted', async () => {
            const { current, ...extra } = history(AFTER_REVISION);
            await create({ artifact: summary('PROPOSED', { currentRevisionId: current }), detail: detailOf(sample(), extra, current) });
            store.revert.mockResolvedValue(true);
            root().querySelector<HTMLButtonElement>('.edit-history li:first-child button')!.click();
            expect(store.revert).toHaveBeenCalledWith(ids.first, BEFORE_REVISION);
            await create({ artifact: summary('PROPOSED', { currentRevisionId: current }), detail: detailOf(sample(), extra, current), busy: true });
            const button = root().querySelector<HTMLButtonElement>('.edit-history li:first-child button')!;
            expect(button.getAttribute('aria-disabled')).toBe('true');
            button.click();
            expect(store.revert).not.toHaveBeenCalled();
            await create({ artifact: summary('PROPOSED', { currentRevisionId: current }), detail: detailOf(sample(), extra, current), sessionState: 'CANCELLED' });
            root().querySelector<HTMLButtonElement>('.edit-history li:first-child button')!.click();
            expect(store.revert).not.toHaveBeenCalled();
        });
    });

    describe('the actions under an image or an audio', () => {
        const group = (kind: 'image' | 'audio'): HTMLElement | null => {
            // An attribution line may sit between the image and its actions.
            let next = block(kind === 'image' ? 4 : 5).nextElementSibling as HTMLElement | null;
            while (next !== null && next.classList.contains('image-credit')) next = next.nextElementSibling as HTMLElement | null;
            return next;
        };
        const names = (element: HTMLElement | null): string[] => [...(element?.querySelectorAll('button') ?? [])].map(button => button.textContent!.trim());
        /** The image of the sample is a search slot (only those offer «Найти похожее»); the audio slot is the contract's. */
        const searchSlot = (mode: 'search' | 'generate' = 'search') => ({ ...clone(examples['mediaSlotImageSearch']), nodeId: newId(4), mode });
        const slots = (mode: 'search' | 'generate' = 'search') => ({ detail: detailOf(sample(), { mediaSlots: [...clone(examples['artifactDetailItem']).mediaSlots, searchSlot(mode)] }) });

        it('offers «Найти похожее», «Создать» and «Убрать» for an image and «Озвучить заново» and «Убрать» for an audio, labelled with what they are about', async () => {
            await create({ ...slots(), capabilities: capabilities({ imageSearch: { available: true, reason: null }, imageGeneration: { available: true, reason: null },
                textToSpeech: { available: true, reason: null } }) });
            expect(names(group('image'))).toEqual(['Найти похожее', 'Создать', 'Убрать']);
            expect(names(group('audio'))).toEqual(['Озвучить заново', 'Убрать']);
            expect(group('image')!.getAttribute('aria-label')).toBe('Действия с изображением: схема глаголов');
            expect(group('audio')!.getAttribute('aria-label')).toBe('Действия с аудио: Произношение');
            expect(group('image')!.querySelector('app-toggletip')).toBeNull();
        });

        it('shows the actions of a capability that is off disabled with the reason in a toggletip, and «Убрать» works', async () => {
            await create({ ...slots(), capabilities: capabilities({ textToSpeech: { available: false, reason: 'TEMPORARILY_UNAVAILABLE' } }) });
            const image = group('image')!;
            const disabled = [...image.querySelectorAll<HTMLButtonElement>('.media-unavailable > button')];
            expect(disabled.map(button => button.textContent!.trim())).toEqual(['Найти похожее', 'Создать']);
            expect(disabled.every(button => button.getAttribute('aria-disabled') === 'true')).toBe(true);
            expect(image.querySelectorAll('app-toggletip')).toHaveLength(2);
            expect(image.querySelector('app-toggletip .bubble')!.textContent).toContain('Подбор изображений появится позже');
            expect(group('audio')!.querySelector('app-toggletip .bubble')!.textContent).toBe('Озвучивание сейчас временно недоступно. Попробуйте позже.');
            const remove = [...image.querySelectorAll('button')].find(button => button.textContent!.trim() === 'Убрать')!;
            remove.click();
            await settle();
            expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'REMOVE_MEDIA', nodeIds: [newId(4)], anchorBefore: newId(3), anchorAfter: newId(5) });
        });

        it('sends the audio redo to the edit API when its capability is on, and opens the search panel for an image', async () => {
            await create({ ...slots(), capabilities: capabilities({ imageSearch: { available: true, reason: null }, textToSpeech: { available: true, reason: null } }) });
            // «Найти похожее» opens its own panel: the search is sent from there (see «image search» below).
            [...group('image')!.querySelectorAll('button')].find(button => button.textContent!.trim() === 'Найти похожее')!.click();
            await settle();
            expect(store.edit).not.toHaveBeenCalled();
            expect(root().querySelector('app-image-search-panel')).not.toBeNull();
            store.edit.mockResolvedValue({ ok: true, turn: parseTurn() });
            [...group('audio')!.querySelectorAll('button')].find(button => button.textContent!.trim() === 'Озвучить заново')!.click();
            await settle();
            // The audio too has its own panel; «Озвучить» in the voice it has sends no voice.
            expect(root().querySelector('app-audio-redo-panel')).not.toBeNull();
            [...root().querySelectorAll<HTMLButtonElement>('app-audio-redo-panel button')].find(button => button.textContent!.trim() === 'Озвучить')!.click();
            await settle();
            expect(store.edit).toHaveBeenLastCalledWith(ids.first, { action: 'AUDIO_REGENERATE', nodeIds: [newId(5)], anchorBefore: newId(4), anchorAfter: newId(6) });
        });

        it('offers no search under an image that is not a search slot (generate, or no slot at all), and keeps its other actions', async () => {
            const on = capabilities({ imageSearch: { available: true, reason: null }, imageGeneration: { available: true, reason: null } });
            await create({ ...slots('generate'), capabilities: on });
            expect(names(group('image'))).toEqual(['Создать', 'Убрать']);
            await create({ capabilities: on });
            expect(names(group('image'))).toEqual(['Создать', 'Убрать']);
            expect(root().querySelector('.image-credit, details.variants, .slot-failed')).toBeNull();
        });

        it('shows nothing under media of a material that is not an editable proposal, and does nothing while a command runs', async () => {
            await create({ artifact: summary('REVISING') });
            expect(root().querySelector('.media-actions')).toBeNull();
            await create({ busy: true, capabilities: capabilities({ imageSearch: { available: true, reason: null } }) });
            expect(root().querySelector('.media-actions')).toBeNull();
            await create({ sessionState: 'CANCELLED' });
            expect(names(group('image'))).toContain('Убрать');
            await create({ artifact: parseArtifactSummary({ ...artifactWith(ids.first, 0, 'PROPOSED', { currentRevisionId: BEFORE_REVISION }), targetKind: 'EXERCISE' }) });
            expect(root().querySelector('.media-actions')).toBeNull();
        });
    });
    describe('image search (AI-10, #296)', () => {
        const IMAGE = newId(4);
        const FIRST = 'ca0d0000-0000-4000-8000-000000000001';
        const SECOND = 'ca0d0000-0000-4000-8000-000000000002';
        const slotOf = (change: (slot: any) => void = () => undefined) => {
            const slot = clone(examples['mediaSlotImageSearch']);
            slot.nodeId = IMAGE;
            change(slot);
            return slot;
        };
        const failedSlot = (errorCode: string) => slotOf(slot => { slot.state = 'FAILED'; slot.errorCode = errorCode; slot.attribution = null; slot.candidates = []; });
        const searchTurn = (change: Record<string, unknown> = {}) => turnOf({ action: 'IMAGE_SEARCH', preset: null, instruction: 'лиса зимой', targetNodeIds: [IMAGE], ...change });
        const on = { imageSearch: { available: true, reason: null } } as const;
        const withSlot = (slot: unknown, extra: Record<string, unknown> = {}) => ({ detail: detailOf(sample(), { mediaSlots: [slot], ...extra }), capabilities: capabilities(on) });
        const applied = (slot: unknown = slotOf(), chosen = FIRST) => {
            const held = clone(slot as any);
            for (const candidate of held.candidates) candidate.chosen = candidate.candidateId === chosen;
            return { artifact: summary('PROPOSED', { currentRevisionId: AFTER_REVISION }), capabilities: capabilities(on),
                detail: detailOf(sample(), { mediaSlots: [held], turns: [searchTurn()], revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'MEDIA']) }, AFTER_REVISION) };
        };
        const memo = (change: Partial<EditMemo> = {}): EditMemo => ({ turnId: turnOf()['turnId'] as string, baseRevisionId: BEFORE_REVISION, dismissed: false, announced: true,
            ask: { action: 'IMAGE_SEARCH', nodeIds: [IMAGE], anchorBefore: newId(3), anchorAfter: newId(5), instruction: 'лиса зимой' }, ...change });
        const panel = (): HTMLElement | null => root().querySelector('app-image-search-panel');
        const field = (): HTMLInputElement | null => root().querySelector<HTMLInputElement>('app-image-search-panel input');
        const panelButton = (label: string): HTMLButtonElement => [...panel()!.querySelectorAll('button')].find(button => button.textContent!.trim() === label)!;
        const radios = (): HTMLInputElement[] => [...root().querySelectorAll<HTMLInputElement>('app-image-variants input[type="radio"]')];
        const strip = (): HTMLElement | null => root().querySelector('.rewrite-strip');
        const typeInto = async (text: string): Promise<void> => {
            field()!.value = text;
            field()!.dispatchEvent(new Event('input'));
            await settle();
        };

        describe('the panel under the image', () => {
            it('opens inline under the image, not as a dialog: the field is focused, its placeholder is the description of the image, nothing is sent yet', async () => {
                await create(withSlot(slotOf()));
                const open = labelled('Найти похожее')!;
                expect(open.getAttribute('aria-expanded')).toBe('false');
                open.click();
                await settle();
                expect(panel()).not.toBeNull();
                expect(root().querySelector('dialog, [role="dialog"]')).toBeNull();
                expect(open.getAttribute('aria-expanded')).toBe('true');
                expect(field()!.placeholder).toBe('схема глаголов');
                expect(window.document.activeElement).toBe(field());
                expect(panel()!.textContent).toContain('1 кредит из ИИ-бюджета');
                expect(store.edit).not.toHaveBeenCalled();
                // It is in the flow of the document, after the image and its actions.
                expect(block(4).nextElementSibling!.classList.contains('image-credit') || block(4).nextElementSibling!.classList.contains('media-actions')).toBe(true);
                expect(panel()!.previousElementSibling!.classList.contains('media-actions')).toBe(true);
            });

            it('sends the query trimmed on the image, with the blocks around it, and an empty field as no instruction', async () => {
                await create(withSlot(slotOf()));
                labelled('Найти похожее')!.click();
                await settle();
                await typeInto('  лиса зимой ');
                panelButton('Искать').click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'IMAGE_SEARCH', nodeIds: [IMAGE], anchorBefore: newId(3), anchorAfter: newId(5), instruction: 'лиса зимой' });
                await create(withSlot(slotOf()));
                labelled('Найти похожее')!.click();
                await settle();
                field()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'IMAGE_SEARCH', nodeIds: [IMAGE], anchorBefore: newId(3), anchorAfter: newId(5), instruction: null });
            });

            it('does not send on the Enter of an IME composition', async () => {
                await create(withSlot(slotOf()));
                labelled('Найти похожее')!.click();
                await settle();
                await typeInto('きつね');
                field()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true }));
                await settle();
                expect(store.edit).not.toHaveBeenCalled();
            });

            it('keeps the panel open and says why when the search is refused', async () => {
                await create(withSlot(slotOf()));
                store.edit.mockResolvedValue({ ok: false, aborted: false, message: 'Не хватает лимита ИИ на поиск изображения.' });
                labelled('Найти похожее')!.click();
                await settle();
                panelButton('Искать').click();
                await settle();
                expect(panel()!.querySelector('.panel-error')!.textContent).toBe('Не хватает лимита ИИ на поиск изображения.');
                expect(panelButton('Искать').getAttribute('aria-disabled')).toBeNull();
                expect(store.notify).not.toHaveBeenCalled();
            });

            it('«Отмена» and Esc close it and give focus back to «Найти похожее»', async () => {
                await create(withSlot(slotOf()));
                labelled('Найти похожее')!.click();
                await settle();
                panelButton('Отмена').click();
                await settle();
                expect(panel()).toBeNull();
                expect(window.document.activeElement).toBe(labelled('Найти похожее'));
                labelled('Найти похожее')!.click();
                await settle();
                field()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
                await settle();
                expect(panel()).toBeNull();
                expect(window.document.activeElement).toBe(labelled('Найти похожее'));
            });

            it('while the search runs it says so in a status, keeps the image where it was and disables the controls with aria-disabled', async () => {
                await create(withSlot(slotOf()));
                // The server takes the turn: the artifact is REVISING and its detail lists the turn when the answer arrives.
                store.edit.mockImplementation(async () => {
                    fixture.componentRef.setInput('artifact', summary('REVISING'));
                    fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [slotOf()], turns: [searchTurn({ status: 'RUNNING', resultRevisionId: null })] }));
                    return { ok: true, turn: parseTurn() };
                });
                labelled('Найти похожее')!.click();
                await settle();
                panelButton('Искать').click();
                await settle();
                expect(panel()).not.toBeNull();
                expect(panel()!.querySelector('[role="status"]')!.textContent).toBe('Ищу похожие изображения…');
                expect(field()!.getAttribute('aria-disabled')).toBe('true');
                expect(panelButton('Искать').getAttribute('aria-disabled')).toBe('true');
                expect(block(4).getAttribute('aria-busy')).toBe('true');
                expect(block(4).getAttribute('style')).toBeNull();
                expect(root().querySelector('.rewriting-caption')).toBeNull();
                // It ends: the panel goes, and focus lands on the variants it found.
                fixture.componentRef.setInput('artifact', summary('PROPOSED', { currentRevisionId: AFTER_REVISION }));
                store.edits.set({ [ids.first]: memo() });
                fixture.componentRef.setInput('detail', applied().detail);
                await settle();
                await settle();
                expect(panel()).toBeNull();
                expect(window.document.activeElement).toBe(radios().find(radio => radio.checked));
            });

            it('announces the end of a search in one polite region of the document, after the panel is gone', async () => {
                await create({ capabilities: capabilities(on), artifact: summary('REVISING'),
                    detail: detailOf(sample(), { mediaSlots: [slotOf()], turns: [searchTurn({ status: 'RUNNING', resultRevisionId: null })] }) });
                const region = root().querySelector<HTMLElement>(':scope > p[role="status"]')!;
                expect(region.getAttribute('aria-live')).toBe('polite');
                expect(region.textContent).toBe('');
                fixture.componentRef.setInput('artifact', summary('PROPOSED', { currentRevisionId: AFTER_REVISION }));
                fixture.componentRef.setInput('detail', applied().detail);
                await settle();
                expect(root().querySelector<HTMLElement>(':scope > p[role="status"]')).toBe(region);
                expect(region.textContent).toBe('Нашла 3 варианта, выбран первый.');
                // The next search clears it, and a failure says why and that nothing changed.
                fixture.componentRef.setInput('artifact', summary('REVISING', { currentRevisionId: AFTER_REVISION }));
                fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [slotOf()], turns: [searchTurn({ status: 'RUNNING', resultRevisionId: null })] }, AFTER_REVISION));
                await settle();
                expect(region.textContent).toBe('');
                fixture.componentRef.setInput('artifact', summary('PROPOSED', { currentRevisionId: AFTER_REVISION }));
                fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [slotOf()],
                    turns: [searchTurn({ status: 'FAILED', errorCode: 'NO_RESULT', resultRevisionId: null })] }, AFTER_REVISION));
                await settle();
                expect(region.textContent).toBe('Не нашлось подходящих изображений. Изображение не изменилось, лимит не списан.');
            });

            it('says «Ищу похожие изображения…» in a status when the search runs without a panel (another tab, a reload)', async () => {
                await create({ artifact: summary('REVISING'), capabilities: capabilities(on),
                    detail: detailOf(sample(), { mediaSlots: [slotOf(slot => { slot.attribution = null; })], turns: [searchTurn({ status: 'QUEUED', resultRevisionId: null })] }) });
                const caption = root().querySelector('.rewriting-caption')!;
                expect(caption.getAttribute('role')).toBe('status');
                expect(caption.textContent).toBe('Ищу похожие изображения…');
                expect(root().querySelector('.media-actions, details.variants')).toBeNull();
            });

            it('offers «Найти похожее» disabled with its reason in a toggletip when the capability is off, and in a stopped session', async () => {
                await create({ detail: detailOf(sample(), { mediaSlots: [slotOf()] }), capabilities: capabilities({ imageSearch: { available: false, reason: 'DISABLED' } }) });
                const off = root().querySelector<HTMLButtonElement>('.media-unavailable > button')!;
                expect(off.textContent!.trim()).toBe('Найти похожее');
                expect(off.getAttribute('aria-disabled')).toBe('true');
                off.click();
                await settle();
                expect(panel()).toBeNull();
                expect(root().querySelector('.media-unavailable .bubble')!.textContent).toContain('Подбор изображений появится позже');
                await create({ ...withSlot(slotOf()), sessionState: 'CANCELLED' });
                expect(root().querySelector('.media-unavailable .bubble')!.textContent).toContain('Работа остановлена');
                root().querySelector<HTMLButtonElement>('.media-unavailable > button')!.click();
                await settle();
                expect(panel()).toBeNull();
            });

            it('does not open while a command runs', async () => {
                await create({ ...withSlot(slotOf()), busy: true });
                expect(root().querySelector('.media-actions')).toBeNull();
                expect(panel()).toBeNull();
            });
        });

        describe('the variants', () => {
            it('lists the READY candidates as a radio group named «Варианты» under the image, open right after a search, the chosen one checked', async () => {
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                const details = root().querySelector<HTMLDetailsElement>('details.variants')!;
                expect(details.hasAttribute('open')).toBe(true);
                expect(details.querySelector('summary')!.textContent).toContain('Варианты (3)');
                expect(details.querySelector('legend')!.textContent).toBe('Варианты');
                expect(radios().map(radio => radio.checked)).toEqual([true, false, false]);
                expect(details.contains(radios()[0]!)).toBe(true);
            });

            it('keeps the variants closed on a page that did not make the search, and has none for a single candidate or a proposal that cannot change', async () => {
                await create({ ...withSlot(slotOf()) });
                const details = root().querySelector<HTMLDetailsElement>('details.variants')!;
                expect(details.hasAttribute('open')).toBe(false);
                await create(withSlot(slotOf(slot => { slot.candidates = [slot.candidates[0]]; })));
                expect(root().querySelector('details.variants')).toBeNull();
                await create({ ...withSlot(slotOf()), artifact: summary('FAILED') });
                expect(root().querySelector('details.variants')).toBeNull();
            });

            const useButton = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('app-image-variants .commit-button')!;

            it('does not call the API on arrow keys or clicks: the radios only change the local choice', async () => {
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                radios()[0]!.focus();
                radios()[1]!.click();
                radios()[2]!.click();
                await settle();
                expect(store.selectCandidate).not.toHaveBeenCalled();
                expect(radios().map(radio => radio.checked)).toEqual([false, false, true]);
                expect(root().querySelector('app-image-variants fieldset')!.getAttribute('aria-disabled')).toBeNull();
            });

            it('commits with «Использовать это изображение» through the store with the slot key and the candidate, saying «Сохраняю выбор…» and keeping focus on the checked radio', async () => {
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                let finish: (value: { ok: true }) => void = () => undefined;
                store.selectCandidate.mockReturnValue(new Promise(resolve => { finish = resolve; }));
                radios()[1]!.click();
                await settle();
                useButton().click();
                await settle();
                expect(store.selectCandidate).toHaveBeenCalledWith(ids.first, 'i1', SECOND);
                expect(root().querySelector('app-image-variants fieldset')!.getAttribute('aria-disabled')).toBe('true');
                expect(root().querySelector('app-image-variants [role="status"]')!.textContent).toBe('Сохраняю выбор…');
                useButton().click();
                expect(store.selectCandidate).toHaveBeenCalledTimes(1);
                // The answer arrives as a new revision with the second candidate chosen.
                fixture.componentRef.setInput('detail', applied(slotOf(), SECOND).detail);
                finish({ ok: true });
                await settle();
                await settle();
                expect(root().querySelector('app-image-variants fieldset')!.getAttribute('aria-disabled')).toBeNull();
                expect(window.document.activeElement).toBe(radios().find(radio => radio.checked));
                expect(radios().map(radio => radio.checked)).toEqual([false, true, false]);
            });

            it('keeps the variants open after a choice is saved (the search strip goes with the new revision), focus on the checked radio, and respects a group the owner closed', async () => {
                const NEXT = '4e700000-0000-4000-8000-000000000009';
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                const details = (): HTMLDetailsElement => root().querySelector<HTMLDetailsElement>('details.variants')!;
                expect(details().open).toBe(true);
                store.selectCandidate.mockResolvedValue({ ok: true });
                radios()[1]!.click();
                await settle();
                useButton().click();
                // The answer is a new revision: the turn of the search no longer made the revision on screen, so its strip is gone.
                const held = clone(slotOf());
                for (const candidate of held.candidates) candidate.chosen = candidate.candidateId === SECOND;
                fixture.componentRef.setInput('artifact', summary('PROPOSED', { currentRevisionId: NEXT }));
                fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [held], turns: [searchTurn()],
                    revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'MEDIA'], [NEXT, 'MEDIA']) }, NEXT));
                await settle();
                await settle();
                expect(strip()).toBeNull();
                expect(details().open).toBe(true);
                expect(window.document.activeElement).toBe(radios().find(radio => radio.checked));
                expect(radios().map(radio => radio.checked)).toEqual([false, true, false]);
                // The owner closes it: a later read of the artifact does not open it again.
                details().open = false;
                details().dispatchEvent(new Event('toggle'));
                fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [clone(held)], turns: [searchTurn()],
                    revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'MEDIA'], [NEXT, 'MEDIA']) }, NEXT));
                await settle();
                expect(details().open).toBe(false);
            });

            it('shows a refused commit in the group and keeps focus on the checked radio', async () => {
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                store.selectCandidate.mockResolvedValue({ ok: false, message: 'Материал обновился, пока вы выбирали.' });
                radios()[1]!.click();
                await settle();
                radios()[1]!.focus();
                useButton().click();
                await settle();
                await settle();
                expect(root().querySelector('app-image-variants .error')!.textContent).toBe('Материал обновился, пока вы выбирали.');
                expect(window.document.activeElement).toBe(radios().find(radio => radio.checked));
                expect(store.notify).not.toHaveBeenCalled();
            });

            it('does not fight a variants group the user closed: focus falls back to its summary', async () => {
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                store.selectCandidate.mockResolvedValue({ ok: false, message: 'x' });
                radios()[1]!.click();
                await settle();
                const details = root().querySelector<HTMLDetailsElement>('details.variants')!;
                useButton().click();
                details.removeAttribute('open');
                await settle();
                await settle();
                expect(window.document.activeElement).not.toBe(window.document.body);
                expect(details.hasAttribute('open')).toBe(false);
            });

            it('draws the picture of a candidate through the asset API, and links only to https pages in a new tab', async () => {
                await create(withSlot(slotOf(slot => { slot.candidates[1].sourcePageUrl = 'http://commons.wikimedia.org/wiki/File:Fox_2.jpg'; })));
                const links = [...root().querySelectorAll<HTMLAnchorElement>('app-image-variants a')];
                expect(links.map(link => link.getAttribute('href'))).toEqual(['https://pixabay.com/photos/fox-1/', 'https://commons.wikimedia.org/wiki/File:Fox_3.jpg']);
                expect(links.every(link => link.target === '_blank' && link.rel === 'noopener noreferrer')).toBe(true);
                expect(root().querySelectorAll('app-image-candidate-thumb')).toHaveLength(3);
                expect(root().querySelector('app-image-variants img[src^="https://pixabay"]')).toBeNull();
            });
        });

        describe('the attribution and the strip', () => {
            it('writes «Фото: автор · источник · лицензия» in small text right under a READY found image, and nothing for an image without one', async () => {
                await create(withSlot(slotOf()));
                const credit = root().querySelector('.image-credit')!;
                expect(credit.textContent).toBe('Фото: Ann · Pixabay · Pixabay Content License');
                expect(block(4).nextElementSibling).toBe(credit);
                await create(withSlot(slotOf(slot => { slot.attribution.author = ''; slot.attribution.source = 'STUB'; slot.attribution.license = ''; })));
                expect(root().querySelector('.image-credit')!.textContent).toBe('Тестовый источник');
                await create({ detail: detailOf(sample(), { mediaSlots: [slotOf(slot => { slot.attribution = null; slot.candidates = []; })] }) });
                expect(root().querySelector('.image-credit')).toBeNull();
            });

            it('keeps the attribution where the proposal can no longer change (a command runs, the artifact is revising)', async () => {
                await create({ ...withSlot(slotOf()), busy: true });
                expect(root().querySelector('.image-credit')).not.toBeNull();
                await create({ ...withSlot(slotOf()), artifact: summary('REVISING') });
                expect(root().querySelector('.image-credit')).not.toBeNull();
            });

            it('says «Подобрано другое изображение» with «Оставить», «Вернуть» and «Ещё раз» (no text diff for a picture)', async () => {
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                expect(strip()!.getAttribute('aria-label')).toBe('Подобранное изображение');
                expect(strip()!.querySelector('.strip-state')!.textContent).toBe('Подобрано другое изображение');
                expect([...strip()!.querySelectorAll('button')].map(button => button.textContent!.trim())).toEqual(['Оставить', 'Вернуть', 'Ещё раз']);
                store.revert.mockResolvedValue(true);
                labelled('Вернуть')!.click();
                await settle();
                expect(store.revert).toHaveBeenCalledWith(ids.first, BEFORE_REVISION);
            });

            it('«Ещё раз» opens the panel again with the previous query, focus in the field', async () => {
                await create(applied());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                labelled('Ещё раз')!.click();
                await settle();
                expect(store.edit).not.toHaveBeenCalled();
                expect(field()!.value).toBe('лиса зимой');
                expect(window.document.activeElement).toBe(field());
                panelButton('Отмена').click();
                await settle();
                expect(window.document.activeElement).toBe(labelled('Ещё раз'));
            });

            it('tells that a search found nothing and that the picture did not change', async () => {
                await create({ capabilities: capabilities(on), detail: detailOf(sample(), { mediaSlots: [slotOf()],
                    turns: [searchTurn({ status: 'FAILED', errorCode: 'NO_RESULT', resultRevisionId: null })] }) });
                store.edits.set({ [ids.first]: memo() });
                await settle();
                expect(strip()!.classList.contains('is-failed')).toBe(true);
                expect(strip()!.textContent).toContain('Не нашлось подходящих изображений. Изображение не изменилось, лимит не списан.');
                expect([...strip()!.querySelectorAll('button')].map(button => button.textContent!.trim())).toEqual(['Ещё раз', 'Закрыть']);
                await create({ capabilities: capabilities(on), detail: detailOf(sample(), { mediaSlots: [slotOf()],
                    turns: [searchTurn({ status: 'FAILED', errorCode: 'PROVIDER_UNAVAILABLE', resultRevisionId: null })] }) });
                store.edits.set({ [ids.first]: memo() });
                await settle();
                expect(strip()!.textContent).toContain('Источники изображений сейчас недоступны.');
            });
        });

        describe('a slot whose search failed', () => {
            const frame = (): HTMLElement | null => root().querySelector('.slot-failed');
            const names = (): string[] => [...frame()!.querySelectorAll('button')].filter(button => button.closest('app-toggletip') === null)
                .map(button => button.textContent!.trim());

            it('says why in the words of the issue and offers «Повторить», «Заменить» and «Убрать блок» instead of the usual actions', async () => {
                const reasons: Record<string, string> = { NO_RESULT: 'Не нашлось изображений со свободной лицензией.', PROVIDER_UNAVAILABLE: 'Источники изображений сейчас недоступны.',
                    VERIFICATION_REJECTED: 'Файл не прошёл проверку.', DEADLINE_EXCEEDED: 'Поиск занял слишком долго.' };
                for (const [code, text] of Object.entries(reasons)) {
                    await create(withSlot(failedSlot(code)));
                    expect(frame()!.querySelector('.slot-failed-text')!.textContent, code).toBe(text);
                    expect(names()).toEqual(['Повторить', 'Заменить…', 'Убрать блок']);
                    expect(frame()!.getAttribute('role')).toBe('group');
                    expect(root().querySelector('.media-actions[aria-label^="Действия с изображением"]')).toBeNull();
                }
            });

            it('«Повторить» sends the search without an instruction', async () => {
                await create(withSlot(failedSlot('NO_RESULT')));
                labelled('Повторить')!.click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'IMAGE_SEARCH', nodeIds: [IMAGE], anchorBefore: newId(3), anchorAfter: newId(5), instruction: null });
                store.edit.mockResolvedValue({ ok: false, aborted: false, message: 'Материал обновился.' });
                labelled('Повторить')!.click();
                await settle();
                expect(store.notify).toHaveBeenCalledWith('Материал обновился.');
            });

            it('«Заменить» opens the panel with an empty field and focus in it; «Отмена» returns focus to «Заменить»', async () => {
                await create(withSlot(failedSlot('NO_RESULT')));
                labelled('Заменить…')!.click();
                await settle();
                expect(field()!.value).toBe('');
                expect(window.document.activeElement).toBe(field());
                await typeInto('красная лиса');
                panelButton('Искать').click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, expect.objectContaining({ action: 'IMAGE_SEARCH', instruction: 'красная лиса' }));
                await create(withSlot(failedSlot('NO_RESULT')));
                labelled('Заменить…')!.click();
                await settle();
                panelButton('Отмена').click();
                await settle();
                expect(window.document.activeElement).toBe(labelled('Заменить…'));
            });

            it('«Убрать блок» removes the media and moves focus to the document', async () => {
                await create(withSlot(failedSlot('NO_RESULT')));
                labelled('Убрать блок')!.click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'REMOVE_MEDIA', nodeIds: [IMAGE], anchorBefore: newId(3), anchorAfter: newId(5) });
                expect(window.document.activeElement).toBe(host);
            });

            it('keeps «Повторить» disabled with the reason when image search is off, and «Убрать блок» working', async () => {
                await create({ detail: detailOf(sample(), { mediaSlots: [failedSlot('PROVIDER_UNAVAILABLE')] }), capabilities: capabilities({ imageSearch: { available: false, reason: 'TEMPORARILY_UNAVAILABLE' } }) });
                expect(names()).toEqual(['Повторить', 'Убрать блок']);
                expect(frame()!.querySelector('.media-unavailable button')!.getAttribute('aria-disabled')).toBe('true');
                expect(frame()!.querySelector('.bubble')!.textContent).toContain('сейчас временно недоступно');
            });
        });
    });
    describe('speech (AI-09, #297)', () => {
        const AUDIO = newId(5);
        const audioSlot = (change: (slot: any) => void = () => undefined) => {
            const slot = clone(examples['artifactDetailItem']).mediaSlots[0];
            slot.nodeId = AUDIO;
            change(slot);
            return slot;
        };
        const failedClip = (errorCode: string) => audioSlot(slot => { slot.state = 'FAILED'; slot.errorCode = errorCode; });
        const redoTurn = (change: Record<string, unknown> = {}) => turnOf({ action: 'AUDIO_REGENERATE', preset: null, instruction: null, targetNodeIds: [AUDIO], voice: 'male', ...change });
        const on = { textToSpeech: { available: true, reason: null } } as const;
        const withClip = (slot: unknown, extra: Record<string, unknown> = {}) => ({ detail: detailOf(sample(), { mediaSlots: [slot], ...extra }), capabilities: capabilities(on) });
        const memo = (change: Partial<EditMemo> = {}): EditMemo => ({ turnId: turnOf()['turnId'] as string, baseRevisionId: BEFORE_REVISION, dismissed: false, announced: true,
            ask: { action: 'AUDIO_REGENERATE', nodeIds: [AUDIO], anchorBefore: newId(4), anchorAfter: newId(6), voice: 'male' }, ...change });
        const panel = (): HTMLElement | null => root().querySelector('app-audio-redo-panel');
        const radios = (): HTMLInputElement[] => [...root().querySelectorAll<HTMLInputElement>('app-audio-redo-panel input[type="radio"]')];
        const panelButton = (label: string): HTMLButtonElement => [...panel()!.querySelectorAll('button')].find(button => button.textContent!.trim() === label)!;
        const strip = (): HTMLElement | null => root().querySelector('.rewrite-strip');
        const region = (): HTMLElement => root().querySelector<HTMLElement>(':scope > p.document-announcement')!;
        const done = (voice = 'male', slot: unknown = audioSlot(held => { held.voice = voice; })) => ({ artifact: summary('PROPOSED', { currentRevisionId: AFTER_REVISION }), capabilities: capabilities(on),
            detail: detailOf(sample(), { mediaSlots: [slot], turns: [redoTurn({ voice })], revisions: revisions([BEFORE_REVISION, 'INITIAL'], [AFTER_REVISION, 'MEDIA']) }, AFTER_REVISION) });

        describe('the caption', () => {
            it('says «Синтезированная речь · женский голос» under a READY clip of the proposal, and «мужской голос» for the other voice', async () => {
                await create(withClip(audioSlot()));
                const caption = root().querySelector('.audio-caption')!;
                expect(caption.textContent).toBe('Синтезированная речь · женский голос');
                expect(block(5).nextElementSibling).toBe(caption);
                await create(withClip(audioSlot(slot => { slot.voice = 'male'; })));
                expect(root().querySelector('.audio-caption')!.textContent).toBe('Синтезированная речь · мужской голос');
                await create(withClip(audioSlot(slot => { slot.voice = null; })));
                expect(root().querySelector('.audio-caption')!.textContent).toBe('Синтезированная речь');
            });

            it('is drawn by the proposal only: the shared renderer says nothing about speech, and a clip that is not READY has no caption', async () => {
                await create(withClip(audioSlot()));
                expect(block(5).textContent).not.toContain('Синтезированная');
                expect(blocks().filter(node => node.textContent!.includes('Синтезированная'))).toHaveLength(0);
                await create(withClip(audioSlot(slot => { slot.state = 'GENERATING'; })));
                expect(root().querySelector('.audio-caption')).toBeNull();
                await create({ detail: detailOf(sample(), { mediaSlots: [] }) });
                expect(root().querySelector('.audio-caption')).toBeNull();
            });

            it('keeps the caption where the proposal can no longer change', async () => {
                await create({ ...withClip(audioSlot()), artifact: summary('REVISING') });
                expect(root().querySelector('.audio-caption')).not.toBeNull();
                await create({ ...withClip(audioSlot()), busy: true });
                expect(root().querySelector('.audio-caption')).not.toBeNull();
            });
        });

        describe('the panel under the audio', () => {
            it('opens inline with the voice of the clip checked and focused, and sends nothing yet', async () => {
                await create(withClip(audioSlot(slot => { slot.voice = 'male'; })));
                const open = labelled('Озвучить заново')!;
                expect(open.getAttribute('aria-expanded')).toBe('false');
                open.click();
                await settle();
                expect(open.getAttribute('aria-expanded')).toBe('true');
                expect(panel()).not.toBeNull();
                expect(root().querySelector('dialog, [role="dialog"]')).toBeNull();
                expect(radios().map(radio => radio.checked)).toEqual([false, true]);
                expect(window.document.activeElement).toBe(radios()[1]);
                expect(panel()!.querySelector('.panel-hint')!.textContent).toContain('до 10 кредитов');
                expect(store.edit).not.toHaveBeenCalled();
            });

            it('sends the redo without a voice when the voice is the clip’s, and with it when it is another one', async () => {
                await create(withClip(audioSlot()));
                labelled('Озвучить заново')!.click();
                await settle();
                panelButton('Озвучить').click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'AUDIO_REGENERATE', nodeIds: [AUDIO], anchorBefore: newId(4), anchorAfter: newId(6) });
                await create(withClip(audioSlot()));
                labelled('Озвучить заново')!.click();
                await settle();
                radios()[1]!.click();
                await settle();
                panelButton('Озвучить').click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'AUDIO_REGENERATE', nodeIds: [AUDIO], anchorBefore: newId(4), anchorAfter: newId(6), voice: 'male' });
            });

            it('keeps the panel open and says why when the redo is refused', async () => {
                await create(withClip(audioSlot()));
                store.edit.mockResolvedValue({ ok: false, aborted: false, message: 'Не хватает лимита ИИ на эту правку.' });
                labelled('Озвучить заново')!.click();
                await settle();
                panelButton('Озвучить').click();
                await settle();
                expect(panel()!.querySelector('.panel-error')!.textContent).toBe('Не хватает лимита ИИ на эту правку.');
                expect(store.notify).not.toHaveBeenCalled();
            });

            it('closes with «Отмена» and Esc and gives focus back to «Озвучить заново»', async () => {
                await create(withClip(audioSlot()));
                labelled('Озвучить заново')!.click();
                await settle();
                panelButton('Отмена').click();
                await settle();
                expect(panel()).toBeNull();
                expect(window.document.activeElement).toBe(labelled('Озвучить заново'));
                labelled('Озвучить заново')!.click();
                await settle();
                radios()[0]!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
                await settle();
                expect(panel()).toBeNull();
                expect(window.document.activeElement).toBe(labelled('Озвучить заново'));
            });

            it('while the clip is made: «Озвучиваю…» in a status, the controls aria-disabled, the clip still there; at the end the panel goes and focus returns', async () => {
                await create(withClip(audioSlot()));
                store.edit.mockImplementation(async () => {
                    fixture.componentRef.setInput('artifact', summary('REVISING'));
                    fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [audioSlot()], turns: [redoTurn({ status: 'RUNNING', resultRevisionId: null })] }));
                    return { ok: true, turn: parseTurn() };
                });
                labelled('Озвучить заново')!.click();
                await settle();
                panelButton('Озвучить').click();
                await settle();
                expect(panel()!.querySelector('[role="status"]')!.textContent).toBe('Озвучиваю…');
                expect(panelButton('Озвучить').getAttribute('aria-disabled')).toBe('true');
                expect(block(5)).not.toBeNull();
                expect(block(5).getAttribute('style')).toBeNull();
                expect(root().querySelector('.rewriting-caption')).toBeNull();
                const finished = done();
                fixture.componentRef.setInput('artifact', finished.artifact);
                fixture.componentRef.setInput('detail', finished.detail);
                store.edits.set({ [ids.first]: memo() });
                await settle();
                await settle();
                expect(panel()).toBeNull();
                expect(window.document.activeElement).toBe(labelled('Озвучить заново'));
            });

            it('says «Озвучиваю…» in a status when the redo runs without a panel (another tab, a reload)', async () => {
                await create({ artifact: summary('REVISING'), capabilities: capabilities(on),
                    detail: detailOf(sample(), { mediaSlots: [audioSlot()], turns: [redoTurn({ status: 'RUNNING', resultRevisionId: null })] }) });
                const caption = root().querySelector('.rewriting-caption')!;
                expect(caption.getAttribute('role')).toBe('status');
                expect(caption.textContent).toBe('Озвучиваю…');
                expect(root().querySelector('.media-actions')).toBeNull();
            });

            it('offers «Озвучить заново» disabled with the reason in a toggletip when speech is off, and in a stopped session', async () => {
                await create({ detail: detailOf(sample(), { mediaSlots: [audioSlot()] }), capabilities: capabilities({ textToSpeech: { available: false, reason: 'TEMPORARILY_UNAVAILABLE' } }) });
                const off = root().querySelector<HTMLButtonElement>('[aria-label^="Действия с аудио"] .media-unavailable > button')!;
                expect(off.textContent!.trim()).toBe('Озвучить заново');
                expect(off.getAttribute('aria-disabled')).toBe('true');
                off.click();
                await settle();
                expect(panel()).toBeNull();
                expect(root().querySelector('[aria-label^="Действия с аудио"] .bubble')!.textContent).toBe('Озвучивание сейчас временно недоступно. Попробуйте позже.');
                await create({ ...withClip(audioSlot()), sessionState: 'CANCELLED' });
                expect(root().querySelector('[aria-label^="Действия с аудио"] .bubble')!.textContent).toContain('Работа остановлена');
            });
        });

        describe('the strip and the announcement', () => {
            it('says «Озвучено заново» with «Оставить», «Вернуть» and «Ещё раз» (no text diff)', async () => {
                await create(done());
                store.edits.set({ [ids.first]: memo() });
                await settle();
                expect(strip()!.getAttribute('aria-label')).toBe('Новая озвучка');
                expect(strip()!.querySelector('.strip-state')!.textContent).toBe('Озвучено заново');
                expect([...strip()!.querySelectorAll('button')].map(button => button.textContent!.trim())).toEqual(['Оставить', 'Вернуть', 'Ещё раз']);
                store.revert.mockResolvedValue(true);
                labelled('Вернуть')!.click();
                await settle();
                expect(store.revert).toHaveBeenCalledWith(ids.first, BEFORE_REVISION);
            });

            it('«Ещё раз» opens the panel with the voice of the clip now and sends nothing yet', async () => {
                await create(done('male'));
                store.edits.set({ [ids.first]: memo() });
                await settle();
                labelled('Ещё раз')!.click();
                await settle();
                expect(store.edit).not.toHaveBeenCalled();
                expect(radios().map(radio => radio.checked)).toEqual([false, true]);
                panelButton('Отмена').click();
                await settle();
                expect(window.document.activeElement).toBe(labelled('Ещё раз'));
            });

            it('tells that the redo failed and that nothing changed, in the strip and in the announcement', async () => {
                await create({ capabilities: capabilities(on), detail: detailOf(sample(), { mediaSlots: [audioSlot()],
                    turns: [redoTurn({ status: 'FAILED', errorCode: 'PROVIDER_UNAVAILABLE', resultRevisionId: null })] }) });
                store.edits.set({ [ids.first]: memo() });
                await settle();
                expect(strip()!.classList.contains('is-failed')).toBe(true);
                expect(strip()!.textContent).toContain('Не удалось озвучить. Запись не изменилась, лимит не списан.');
                expect([...strip()!.querySelectorAll('button')].map(button => button.textContent!.trim())).toEqual(['Ещё раз', 'Закрыть']);
            });

            it('announces the end of a redo once, in the region of the document: «Готово: новая озвучка, мужской голос.» or the failure', async () => {
                await create({ capabilities: capabilities(on), artifact: summary('REVISING'),
                    detail: detailOf(sample(), { mediaSlots: [audioSlot()], turns: [redoTurn({ status: 'RUNNING', resultRevisionId: null })] }) });
                expect(region().textContent).toBe('');
                const finished = done('male');
                fixture.componentRef.setInput('artifact', finished.artifact);
                fixture.componentRef.setInput('detail', finished.detail);
                await settle();
                expect(region().textContent).toBe('Готово: новая озвучка, мужской голос.');
                fixture.componentRef.setInput('artifact', summary('REVISING', { currentRevisionId: AFTER_REVISION }));
                fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [audioSlot()], turns: [redoTurn({ status: 'RUNNING', resultRevisionId: null })] }, AFTER_REVISION));
                await settle();
                expect(region().textContent).toBe('');
                fixture.componentRef.setInput('artifact', summary('PROPOSED', { currentRevisionId: AFTER_REVISION }));
                fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [audioSlot()],
                    turns: [redoTurn({ status: 'FAILED', errorCode: 'DEADLINE_EXCEEDED', resultRevisionId: null })] }, AFTER_REVISION));
                await settle();
                expect(region().textContent).toBe('Не удалось озвучить. Запись не изменилась, лимит не списан.');
            });
        });

        describe('a clip that is being made or failed', () => {
            const frame = (): HTMLElement | null => root().querySelector('.slot-failed');
            const names = (): string[] => [...frame()!.querySelectorAll('button')].filter(button => button.closest('app-toggletip') === null)
                .map(button => button.textContent!.trim());

            it('says «Озвучиваю…» in the paper placeholder while the first synthesis runs', async () => {
                for (const state of ['PENDING', 'GENERATING', 'VERIFYING']) {
                    await create(withClip(audioSlot(slot => { slot.state = state; })));
                    expect(root().querySelector('.audio-status')!.textContent, state).toBe('Озвучиваю…');
                    expect(root().querySelector('.audio-status')!.getAttribute('role')).toBe('status');
                }
                await create(withClip(audioSlot()));
                expect(root().querySelector('.audio-status')).toBeNull();
            });

            it('says why in the words of the issue, and offers «Повторить» and «Убрать блок» instead of the usual actions', async () => {
                const reasons: Record<string, string> = { PROVIDER_UNAVAILABLE: 'Озвучка сейчас недоступна.', VERIFICATION_REJECTED: 'Запись не прошла проверку.',
                    DEADLINE_EXCEEDED: 'Озвучка заняла слишком долго.', USAGE_LIMIT: 'Не хватает лимита на озвучку.' };
                for (const [code, text] of Object.entries(reasons)) {
                    await create(withClip(failedClip(code)));
                    expect(frame()!.querySelector('.slot-failed-text')!.textContent, code).toBe(text);
                    expect(names()).toEqual(['Повторить', 'Убрать блок']);
                    expect(root().querySelector('.media-actions[aria-label^="Действия с аудио"]')).toBeNull();
                    expect(root().querySelector('.audio-caption')).toBeNull();
                }
            });

            it('«Повторить» sends the redo without a voice, and reports a refusal on the page', async () => {
                await create(withClip(failedClip('PROVIDER_UNAVAILABLE')));
                labelled('Повторить')!.click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'AUDIO_REGENERATE', nodeIds: [AUDIO], anchorBefore: newId(4), anchorAfter: newId(6) });
                store.edit.mockResolvedValue({ ok: false, aborted: false, message: 'Материал обновился.' });
                labelled('Повторить')!.click();
                await settle();
                expect(store.notify).toHaveBeenCalledWith('Материал обновился.');
            });

            it('after «Повторить» succeeds focus goes to «Озвучить заново», not to the page', async () => {
                await create(withClip(failedClip('NO_RESULT')));
                store.edit.mockImplementation(async () => {
                    fixture.componentRef.setInput('artifact', summary('REVISING'));
                    fixture.componentRef.setInput('detail', detailOf(sample(), { mediaSlots: [failedClip('PROVIDER_UNAVAILABLE')], turns: [redoTurn({ status: 'RUNNING', resultRevisionId: null, voice: null })] }));
                    return { ok: true, turn: parseTurn() };
                });
                labelled('Повторить')!.click();
                await settle();
                const finished = done();
                fixture.componentRef.setInput('artifact', finished.artifact);
                fixture.componentRef.setInput('detail', finished.detail);
                await settle();
                await settle();
                expect(root().querySelector('.slot-failed')).toBeNull();
                expect(window.document.activeElement).toBe(labelled('Озвучить заново'));
            });

            it('«Убрать блок» removes the media and moves focus to the document', async () => {
                await create(withClip(failedClip('PROVIDER_UNAVAILABLE')));
                labelled('Убрать блок')!.click();
                await settle();
                expect(store.edit).toHaveBeenCalledWith(ids.first, { action: 'REMOVE_MEDIA', nodeIds: [AUDIO], anchorBefore: newId(4), anchorAfter: newId(6) });
                expect(window.document.activeElement).toBe(host);
            });

            it('keeps «Повторить» disabled with the reason when speech is off, and «Убрать блок» working', async () => {
                await create({ detail: detailOf(sample(), { mediaSlots: [failedClip('PROVIDER_UNAVAILABLE')] }), capabilities: capabilities({ textToSpeech: { available: false, reason: 'DISABLED' } }) });
                expect(names()).toEqual(['Повторить', 'Убрать блок']);
                expect(frame()!.querySelector('.media-unavailable button')!.getAttribute('aria-disabled')).toBe('true');
                expect(frame()!.querySelector('.bubble')!.textContent).toContain('Озвучивание появится позже');
            });
        });
    });
});

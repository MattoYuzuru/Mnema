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
        editCost: vi.fn<(...args: unknown[]) => Promise<{ text: string; canStart: boolean; blocked: string | null } | null>>(), notify: vi.fn() };
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
        const group = (kind: 'image' | 'audio'): HTMLElement | null => block(kind === 'image' ? 4 : 5).nextElementSibling as HTMLElement | null;
        const names = (element: HTMLElement | null): string[] => [...(element?.querySelectorAll('button') ?? [])].map(button => button.textContent!.trim());

        it('offers «Найти похожее», «Создать» and «Убрать» for an image and «Озвучить заново» and «Убрать» for an audio, labelled with what they are about', async () => {
            await create({ capabilities: capabilities({ imageSearch: { available: true, reason: null }, imageGeneration: { available: true, reason: null },
                textToSpeech: { available: true, reason: null } }) });
            expect(names(group('image'))).toEqual(['Найти похожее', 'Создать', 'Убрать']);
            expect(names(group('audio'))).toEqual(['Озвучить заново', 'Убрать']);
            expect(group('image')!.getAttribute('aria-label')).toBe('Действия с изображением: схема глаголов');
            expect(group('audio')!.getAttribute('aria-label')).toBe('Действия с аудио: Произношение');
            expect(group('image')!.querySelector('app-toggletip')).toBeNull();
        });

        it('shows the actions of a capability that is off disabled with the reason in a toggletip, and «Убрать» works', async () => {
            await create({ capabilities: capabilities({ textToSpeech: { available: false, reason: 'TEMPORARILY_UNAVAILABLE' } }) });
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

        it('sends the media redo to the edit API when its capability is on, and reports a refusal on the page', async () => {
            await create({ capabilities: capabilities({ imageSearch: { available: true, reason: null }, textToSpeech: { available: true, reason: null } }) });
            store.edit.mockResolvedValue({ ok: false, aborted: false, message: 'Поиск изображений пока недоступен.' });
            [...group('image')!.querySelectorAll('button')].find(button => button.textContent!.trim() === 'Найти похожее')!.click();
            await settle();
            expect(store.edit).toHaveBeenCalledWith(ids.first, expect.objectContaining({ action: 'IMAGE_SEARCH', nodeIds: [newId(4)] }));
            expect(store.notify).toHaveBeenCalledWith('Поиск изображений пока недоступен.');
            store.edit.mockResolvedValue({ ok: true, turn: parseTurn() });
            [...group('audio')!.querySelectorAll('button')].find(button => button.textContent!.trim() === 'Озвучить заново')!.click();
            await settle();
            expect(store.edit).toHaveBeenLastCalledWith(ids.first, expect.objectContaining({ action: 'AUDIO_REGENERATE', nodeIds: [newId(5)] }));
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
});

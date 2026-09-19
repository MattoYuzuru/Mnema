import { splitBlock, toggleMark } from 'prosemirror-commands';
import { history, undo } from 'prosemirror-history';
import { EditorState, TextSelection } from 'prosemirror-state';

import mixedDocumentJson from '../../../../../contracts/content/native-v1/valid/mixed.json';
import { NativeDocument, NativeNode } from '../native-document';
import {
    exportNativeDocument,
    importNativeDocument,
    nativeEditorSchema,
    nativeTextIdentityPlugin,
    parseSafePastedHtml,
    sanitizePastedHtml
} from './native-editor-adapter';

describe('native ProseMirror adapter', () => {
    const mixed = mixedDocumentJson as unknown as NativeDocument;

    it('round-trips the shared golden fixture without exposing ProseMirror state', () => {
        const imported = importNativeDocument(mixed);

        expect(imported.editable).toBeTrue();
        expect(imported.document).not.toBeNull();
        const exported = exportNativeDocument(imported.document!);
        expect(exported).toEqual(mixed);
        expect(JSON.stringify(exported)).not.toContain('selection');
        expect(JSON.stringify(exported)).not.toContain('mnema_text');
    });

    it('preserves future nodes and their unsafe-looking opaque payload semantically', () => {
        const imported = importNativeDocument(mixed);
        const exported = exportNativeDocument(imported.document!);
        const future = findNative(exported.root, 'future_formula');
        const table = findNative(exported.root, 'table');

        expect(future).toEqual(findNative(mixed.root, 'future_formula'));
        expect(table).toEqual(findNative(mixed.root, 'table'));
        expect(imported.document!.toString()).not.toContain('onerror');
    });

    it('keeps one existing text identity and allocates a stable new identity when formatting splits a run', () => {
        const document = simpleDocument('abc');
        const imported = importNativeDocument(document);
        let state = EditorState.create({
            schema: nativeEditorSchema,
            doc: imported.document!,
            plugins: [nativeTextIdentityPlugin]
        });
        const originalId = document.root.content[0]!.content[0]!.id;
        const command = toggleMark(nativeEditorSchema.marks['strong']!);
        state = state.apply(state.tr.setSelection(TextSelection.create(state.doc, 2, 3)));
        command(state, transaction => { state = state.applyTransaction(transaction).state; });

        const first = exportNativeDocument(state.doc);
        const second = exportNativeDocument(state.doc);
        const ids = first.root.content[0]!.content.map(node => node.id);

        expect(new Set(ids).size).toBe(ids.length);
        expect(ids).toContain(originalId);
        expect(second).toEqual(first);
    });

    it('sanitizes pasted HTML and admits only schema content and safe HTTPS links', () => {
        const malicious = '<p onclick="globalThis.pwned=1">Текст <strong>важный</strong>'
            + '<img src=x onerror="globalThis.pwned=2"><script>globalThis.pwned=3</script>'
            + '<svg onload="globalThis.pwned=4"></svg><a href="javascript:alert(1)">ссылка</a></p>';

        const sanitized = sanitizePastedHtml(malicious);
        const parsed = parseSafePastedHtml(malicious);

        expect(sanitized).not.toContain('onclick');
        expect(sanitized).not.toContain('img');
        expect(sanitized).not.toContain('script');
        expect(sanitized).not.toContain('svg');
        expect(sanitized).not.toContain('javascript:');
        expect(parsed.textContent).toContain('Текст важный');
        expect(parsed.textContent).toContain('ссылка');
    });

    it('opens a future root read-only and retains the complete source', () => {
        const future: NativeDocument = structuredClone(mixed);
        (future.root as { version: number }).version = 2;

        const imported = importNativeDocument(future);

        expect(imported.editable).toBeFalse();
        expect(imported.document).toBeNull();
        expect(imported.source).toEqual(future);
    });

    it('allocates a distinct block ID on split and undo restores the exact native document', () => {
        const document = simpleDocument('abc');
        const imported = importNativeDocument(document);
        let state = EditorState.create({
            schema: nativeEditorSchema,
            doc: imported.document!,
            plugins: [history(), nativeTextIdentityPlugin]
        });
        state = state.apply(state.tr.setSelection(TextSelection.create(state.doc, 2)));
        splitBlock(state, transaction => { state = state.applyTransaction(transaction).state; });

        const split = exportNativeDocument(state.doc);
        expect(split.root.content.length).toBe(2);
        expect(new Set(split.root.content.map(node => node.id)).size).toBe(2);

        undo(state, transaction => { state = state.applyTransaction(transaction).state; });
        expect(exportNativeDocument(state.doc)).toEqual(document);
    });

    it('rejects overlong scalars even when hidden inside an opaque node', () => {
        const document = simpleDocument('safe');
        const opaque = document.root.content[0]! as { type: string; version: number; attrs: Record<string, unknown> };
        opaque.type = 'future_block';
        opaque.version = 7;
        opaque.attrs = { payload: 'x'.repeat(32 * 1024 + 1) };

        expect(() => importNativeDocument(document)).toThrowError();
    });

    it('re-identifies a copied opaque subtree so the export remains publishable', () => {
        const imported = importNativeDocument(mixed);
        let state = EditorState.create({
            schema: nativeEditorSchema,
            doc: imported.document!,
            plugins: [nativeTextIdentityPlugin]
        });
        const opaque = state.doc.content.content.find(node => node.type.name === 'unsupported_block'
            && (node.attrs['payload'] as { type?: string }).type === 'table')!;
        state = state.applyTransaction(state.tr.insert(state.doc.content.size, opaque)).state;

        const exported = exportNativeDocument(state.doc);
        const ids = allIds(exported.root);
        const tables = exported.root.content.filter(node => node.type === 'table');
        expect(new Set(ids).size).toBe(ids.length);
        expect(tables.length).toBe(2);
        expect(tables[1]?.attrs).toEqual(tables[0]!.attrs);
        expect(tables[1]?.id).not.toBe(tables[0]!.id);
    });
});

function simpleDocument(text: string): NativeDocument {
    return {
        formatVersion: 1,
        root: {
            id: '00000000-0000-4000-8000-000000000101', type: 'doc', version: 1, attrs: {},
            content: [{
                id: '00000000-0000-4000-8000-000000000102', type: 'paragraph', version: 1, attrs: {},
                content: [{
                    id: '00000000-0000-4000-8000-000000000103', type: 'text', version: 1,
                    attrs: { text, marks: [] }, content: []
                }]
            }]
        }
    };
}

function findNative(node: NativeNode, type: string): NativeNode | null {
    if (node.type === type) return node;
    for (const child of node.content) {
        const found = findNative(child, type);
        if (found !== null) return found;
    }
    return null;
}

function allIds(node: NativeNode): readonly string[] {
    return [node.id, ...node.content.flatMap(allIds)];
}

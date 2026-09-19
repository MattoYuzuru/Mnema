import { NativeDocument, NativeNode } from '../native-document';
import { planNativeStructuralEdits } from './native-structural-edits';

describe('planNativeStructuralEdits', () => {
    const id = (suffix: string) => `00000000-0000-4000-8000-${suffix.padStart(12, '0')}`;
    const node = (suffix: string, content: readonly NativeNode[] = []): NativeNode => ({
        id: id(suffix), type: 'paragraph', version: 1, attrs: {}, content
    });
    const document = (content: readonly NativeNode[]): NativeDocument => ({
        formatVersion: 1, root: { id: id('1'), type: 'doc', version: 1, attrs: {}, content }
    });

    it('does not invent structural intent for text and attribute changes', () => {
        const before = document([node('2', [node('3')])]);
        const after = structuredClone(before);
        (after.root.content[0]!.attrs as { label?: string }).label = 'changed';
        expect(planNativeStructuralEdits(before, after)).toEqual([]);
    });

    it('plans ordered insert, move and delete intent', () => {
        const before = document([node('2'), node('3'), node('4')]);
        const after = document([node('3'), node('5'), node('2')]);
        expect(planNativeStructuralEdits(before, after)).toEqual([
            { type: 'delete', nodeId: id('4') },
            { type: 'move', nodeId: id('3'), parentId: id('1'), childIndex: 0 },
            { type: 'insert', nodeId: id('5'), parentId: id('1'), childIndex: 1 }
        ]);
    });

    it('re-inserts an existing subtree when its new parent does not exist yet', () => {
        const paragraph = node('2');
        const before = document([paragraph]);
        const wrapper = { ...node('3', [paragraph]), type: 'blockquote' };
        const after = document([wrapper]);
        expect(planNativeStructuralEdits(before, after)).toEqual([
            { type: 'delete', nodeId: id('2') },
            { type: 'insert', nodeId: id('3'), parentId: id('1'), childIndex: 0 }
        ]);
    });
});

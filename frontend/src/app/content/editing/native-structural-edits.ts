import { NativeDocument } from '../native-document';

export type NativeStructuralEdit =
    | { readonly type: 'insert'; readonly nodeId: string; readonly parentId: string; readonly childIndex: number }
    | { readonly type: 'delete'; readonly nodeId: string }
    | { readonly type: 'move'; readonly nodeId: string; readonly parentId: string; readonly childIndex: number };

interface MutableNode {
    id: string;
    content: MutableNode[];
    [key: string]: unknown;
}

const MAX_STRUCTURAL_EDITS = 100;

/** Plans explicit topology intent while leaving attribute/text-only changes to ordinary replacement. */
export function planNativeStructuralEdits(before: NativeDocument, after: NativeDocument): readonly NativeStructuralEdit[] {
    if (before.root.id !== after.root.id) throw new Error('The native document root identity cannot change.');
    const current = structuredClone(before.root) as unknown as MutableNode;
    const target = structuredClone(after.root) as unknown as MutableNode;
    const targetParents = parentMap(target);
    const initialIds = new Set(parentMap(current).keys());
    initialIds.add(current.id);
    const targetIds = new Set(targetParents.keys());
    targetIds.add(target.id);
    const edits: NativeStructuralEdit[] = [];

    const append = (edit: NativeStructuralEdit): void => {
        edits.push(edit);
        if (edits.length > MAX_STRUCTURAL_EDITS) {
            throw new Error(`A single publication may contain at most ${MAX_STRUCTURAL_EDITS} structural edits.`);
        }
    };

    const removeUnavailable = (parent: MutableNode): void => {
        for (let index = parent.content.length - 1; index >= 0; index--) {
            const child = parent.content[index]!;
            const nextParent = targetParents.get(child.id);
            const requiresReinsertion = nextParent !== undefined && nextParent !== parent.id && !initialIds.has(nextParent);
            if (!targetIds.has(child.id) || requiresReinsertion) {
                append({ type: 'delete', nodeId: child.id });
                parent.content.splice(index, 1);
            } else {
                removeUnavailable(child);
            }
        }
    };
    removeUnavailable(current);

    const align = (parent: MutableNode, desired: MutableNode): void => {
        for (let index = 0; index < desired.content.length; index++) {
            const desiredChild = desired.content[index]!;
            let actual = parent.content[index];
            if (actual?.id !== desiredChild.id) {
                const located = locate(current, desiredChild.id);
                if (located === null) {
                    append({ type: 'insert', nodeId: desiredChild.id, parentId: parent.id, childIndex: index });
                    actual = structuredClone(desiredChild);
                    parent.content.splice(index, 0, actual);
                } else {
                    located.parent.content.splice(located.index, 1);
                    append({ type: 'move', nodeId: desiredChild.id, parentId: parent.id, childIndex: index });
                    parent.content.splice(index, 0, located.node);
                    actual = located.node;
                }
            }
            align(actual, desiredChild);
        }
    };
    align(current, target);
    if (!sameTopology(current, target)) throw new Error('Native document topology could not be represented safely.');
    return edits;
}

function parentMap(root: MutableNode): Map<string, string> {
    const result = new Map<string, string>();
    const pending = [root];
    while (pending.length > 0) {
        const parent = pending.pop()!;
        for (const child of parent.content) {
            if (result.has(child.id) || child.id === root.id) throw new Error('Native node identities must be unique.');
            result.set(child.id, parent.id);
            pending.push(child);
        }
    }
    return result;
}

function locate(root: MutableNode, id: string): { node: MutableNode; parent: MutableNode; index: number } | null {
    const pending = [root];
    while (pending.length > 0) {
        const parent = pending.pop()!;
        for (let index = 0; index < parent.content.length; index++) {
            const node = parent.content[index]!;
            if (node.id === id) return { node, parent, index };
            pending.push(node);
        }
    }
    return null;
}

function sameTopology(left: MutableNode, right: MutableNode): boolean {
    return left.id === right.id && left.content.length === right.content.length
        && left.content.every((child, index) => sameTopology(child, right.content[index]!));
}

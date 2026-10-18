/** Semantic JSON values; editor/runtime objects are never part of this contract. */
export type NativeJson = null | boolean | number | string
  | readonly NativeJson[] | { readonly [key: string]: NativeJson };

/**
 * Native-v1 structure shared by the renderer, editor and API boundaries.
 * See contracts/content/native-v1. Types are not validation or authorization:
 * unknown/future nodes remain opaque and grant no rendering capability.
 */
export interface NativeNode {
  readonly [key: string]: NativeJson;
  readonly id: string;
  readonly type: string;
  readonly version: number;
  readonly attrs: { readonly [key: string]: NativeJson };
  readonly content: readonly NativeNode[];
}

export interface NativeDocument {
  readonly formatVersion: 1;
  readonly root: NativeNode;
}

const MEDIA_NODE_TYPES: readonly string[] = ['image', 'audio', 'video'];

/** How many image, audio and video nodes the document holds (the editor tells a side quest when a medium was put in). */
export function nativeMediaCount(document: NativeDocument): number {
  const count = (node: NativeNode): number => (MEDIA_NODE_TYPES.includes(node.type) ? 1 : 0) + node.content.reduce((sum, child) => sum + count(child), 0);
  return count(document.root);
}

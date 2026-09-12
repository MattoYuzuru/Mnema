/** Semantic JSON values; editor/runtime objects are never part of this contract. */
export type NativeJson = null | boolean | number | string
  | readonly NativeJson[] | { readonly [key: string]: NativeJson };

/**
 * Native-v1 structure shared by the renderer and future editor/API adapters.
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

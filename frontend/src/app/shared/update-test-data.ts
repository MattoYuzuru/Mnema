import type { NativeDocument } from '../content/native-document';
import { documentOf, nativeNode } from '../content/rendering/native-renderer.fixtures';

/** Fixtures of the update review specs: nodes with fixed ids, so the same block of two versions is the same node. */
export const nodeId = (index: number): string => `e6000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}`;

export const paragraph = (index: number, text: string) =>
    nativeNode('paragraph', {}, [nativeNode('text', { text, marks: [] }, [], { id: nodeId(index + 1000) })], { id: nodeId(index) });

export const divider = (index: number) => nativeNode('divider', {}, [], { id: nodeId(index) });

export const MINE: NativeDocument = documentOf([
    paragraph(1, 'Глагол ser описывает постоянные свойства.'),
    paragraph(2, 'Глагол estar описывает состояние и место.'),
    paragraph(3, 'Устаревшее замечание.')
]);

/** Paragraph 1 edited, paragraph 2 untouched, paragraph 3 deleted, a divider inserted. */
export const AUTHOR: NativeDocument = documentOf([
    paragraph(1, 'Глагол ser описывает устойчивые свойства.'),
    paragraph(2, 'Глагол estar описывает состояние и место.'),
    divider(4)
]);

export const heading = (index: number, level: number, text: string) =>
    nativeNode('heading', { level }, [nativeNode('text', { text, marks: [] }, [], { id: nodeId(index + 1000) })], { id: nodeId(index) });

/** A material with its own outline: «Спряжение» (level 1) with «Исключения» (level 2) below it. */
export const OUTLINED: NativeDocument = documentOf([
    heading(30, 1, 'Спряжение'),
    paragraph(31, 'Окончания зависят от лица.'),
    heading(32, 2, 'Исключения'),
    paragraph(33, 'Несколько глаголов спрягаются иначе.')
]);

import { LearnerBlock } from './exercise-content.models';

const MAX_LABEL = 60;

/**
 * Short accessible name of one issued item: its text, else the image description, else a neutral media name
 * («Аудио, элемент 3»). `ordinal` is the item's position in the issued list, so the name never changes while the
 * learner moves items around. Long text (a code block) is shortened for the name only; the content is untouched.
 */
export function itemLabel(blocks: readonly LearnerBlock[], ordinal: number): string {
    const text = blocks.find(block => block.kind === 'TEXT');
    if (text?.kind === 'TEXT') {
        const line = text.text.replace(/\s+/gu, ' ').trim();
        return line.length <= MAX_LABEL ? line : `${Array.from(line).slice(0, MAX_LABEL - 1).join('')}…`;
    }
    const image = blocks.find(block => block.kind === 'IMAGE');
    if (image?.kind === 'IMAGE') return image.alt;
    const media = blocks.find(block => block.kind === 'AUDIO' || block.kind === 'VIDEO');
    if (media === undefined) return `Элемент ${ordinal}`;
    return `${media.kind === 'VIDEO' ? 'Видео' : 'Аудио'}, элемент ${ordinal}`;
}

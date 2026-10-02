import { NativeNode } from '../../content/native-document';
import { documentOf, nativeNode } from '../../content/rendering/native-renderer.fixtures';
import { nodeText, textProjections } from './exercise.models';

describe('textProjections of code blocks', () => {
    it('quotes the source of a valid code block with its line breaks and indentation, as the server projects it', () => {
        const code = nativeNode('code_block', { lang: 'sql', source: 'SELECT 1;\n\tFROM t' });
        const prose = nativeNode('paragraph', {}, [nativeNode('text', { text: '  Запрос \n план ' })]);
        const projections = textProjections(documentOf([prose, code]));
        expect(projections.map(projection => projection.nodeId)).toEqual([prose.id, code.id]);
        expect(projections[0]!.text).toBe('Запрос план');
        expect(projections[1]!.text).toBe('SELECT 1;\n\tFROM t');
        expect(projections[1]!.label).toBe('SELECT 1; FROM t');
    });

    it('projects nothing for a retained code block that is not valid version one', () => {
        const legacy: NativeNode = nativeNode('code_block', { language: 'kotlin', source: 'secret' });
        expect(nodeText(legacy)).toBe('');
        expect(textProjections(documentOf([legacy]))).toEqual([]);
    });
});

import codeBlockVectors from '../../../../contracts/content/native-v1/code-block-vectors.json';
import codeDocumentJson from '../../../../contracts/content/native-v1/valid/code.json';
import { NativeJson } from './native-document';
import { NativeDocumentBoundaryError, readNativeDocument, readRetainedNativeDocument } from './native-document-boundary';
import { documentOf, nativeNode } from './rendering/native-renderer.fixtures';

describe('native document boundary: code_block', () => {
    it('accepts the shared fixture on both paths and returns an equal clone', () => {
        expect(readNativeDocument(codeDocumentJson)).toEqual(codeDocumentJson);
        expect(readRetainedNativeDocument(codeDocumentJson)).toEqual(codeDocumentJson);
        expect(readNativeDocument(codeDocumentJson)).not.toBe(codeDocumentJson);
    });

    it('applies the shared vectors: strict rejects, retained keeps the node as it is', () => {
        for (const vector of codeBlockVectors.cases) {
            const document = documentOf([nativeNode('code_block', vector.attrs as unknown as Record<string, NativeJson>)]);
            if (vector.valid) {
                expect(() => readNativeDocument(document), vector.name).not.toThrow();
            } else {
                expect(() => readNativeDocument(document), vector.name).toThrowError(NativeDocumentBoundaryError);
            }
            expect(readRetainedNativeDocument(document), vector.name).toEqual(document);
        }
    });
});

import lexicalVectors from '../../../../../contracts/content/native-v1/lexical-vectors.json';
import { NativeJson, NativeNode } from '../native-document';
import {
    NATIVE_RENDER_LIMITS,
    buildNativeRenderState,
    isAllowedNativeHref,
    isAllowedNativeLang
} from './native-render-state';
import { documentOf, nativeNode } from './native-renderer.fixtures';

describe('native render boundary', () => {
    it('matches every shared native-v1 language vector', () => {
        expect(lexicalVectors.lang.accept.every(isAllowedNativeLang)).toBeTrue();
        expect(lexicalVectors.lang.reject.filter(isAllowedNativeLang)).toEqual([]);
    });

    it('matches every shared native-v1 href vector', () => {
        expect(lexicalVectors.href.accept.filter(value => !isAllowedNativeHref(value))).toEqual([]);
        expect(lexicalVectors.href.reject.filter(isAllowedNativeHref)).toEqual([]);
        expect(isAllowedNativeHref('https://xn--9ca/')).toBeTrue();
    });

    it('accepts valid expanded, mapped IPv6 and a DNS final label containing digits', () => {
        expect(isAllowedNativeHref('https://[2001:0db8:0000:0000:0000:ff00:0042:8329]/source')).toBeTrue();
        expect(isAllowedNativeHref('https://[::ffff:192.0.2.128]:443/source')).toBeTrue();
        expect(isAllowedNativeHref('https://example.tld1/source')).toBeTrue();
    });

    it('preserves mark order without changing the semantic input', () => {
        const input = documentOf([
            nativeNode('paragraph', {}, [
                nativeNode('text', { text: 'order', marks: ['code', 'strong', 'em'] })
            ])
        ]);
        const before = JSON.stringify(input);

        const state = buildNativeRenderState(input);

        expect(state.status).toBe('ready');
        expect(JSON.stringify(input)).toBe(before);
        if (state.status !== 'ready') {
            return;
        }
        const paragraph = state.root.content[0];
        expect(paragraph?.kind).toBe('paragraph');
        if (paragraph?.kind !== 'paragraph') {
            return;
        }
        const text = paragraph.content[0];
        expect(text?.kind).toBe('text');
        if (text?.kind !== 'text') {
            return;
        }
        expect(text.markedText).toEqual({
            kind: 'mark',
            mark: 'code',
            child: {
                kind: 'mark',
                mark: 'strong',
                child: {
                    kind: 'mark',
                    mark: 'em',
                    child: { kind: 'value', value: 'order' }
                }
            }
        });
    });

    it('does not inspect attributes or descendants behind an opaque node boundary', () => {
        const opaqueAttrs: Record<string, NativeJson> = {};
        Object.defineProperty(opaqueAttrs, 'payload', {
            enumerable: true,
            get: () => {
                throw new Error('opaque payload was inspected');
            }
        });
        const hiddenDescendants: NativeNode[] = [];
        Object.defineProperty(hiddenDescendants, '0', {
            enumerable: true,
            get: () => {
                throw new Error('opaque descendants were inspected');
            }
        });
        Object.defineProperty(hiddenDescendants, 'length', { value: 1 });
        const input = documentOf([
            nativeNode('future_formula', opaqueAttrs, hiddenDescendants, { version: 3 })
        ]);

        const state = buildNativeRenderState(input);

        expect(state.status).toBe('ready');
        if (state.status === 'ready') {
            expect(state.root.content[0]?.kind).toBe('opaque');
        }
    });

    it('keeps a future root inert instead of reading its payload', () => {
        const input = documentOf([], 2);
        (input.root.attrs as Record<string, NativeJson>)['unsafe'] = '<script>globalThis.pwned=1</script>';
        (input.root.content as NativeNode[]).push(nativeNode('text', { text: 'must stay hidden' }));

        expect(buildNativeRenderState(input).status).toBe('unsupported-document');
    });

    it('rejects duplicate IDs regardless of UUID spelling case', () => {
        const duplicate = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
        const input = documentOf([
            nativeNode('paragraph', {}, [], { id: duplicate }),
            nativeNode('paragraph', {}, [], { id: duplicate.toUpperCase() })
        ]);

        expect(buildNativeRenderState(input).status).toBe('invalid');
    });

    it('rejects malformed known nodes and nested supported links', () => {
        const malformed = documentOf([nativeNode('link', { href: 'javascript:alert(1)' }, [
            nativeNode('text', { text: 'unsafe' })
        ])]);
        const nested = documentOf([nativeNode('paragraph', {}, [
            nativeNode('link', { href: 'https://example.test' }, [
                nativeNode('link', { href: 'https://example.test/nested' }, [
                    nativeNode('text', { text: 'nested' })
                ])
            ])
        ])]);

        expect(buildNativeRenderState(malformed).status).toBe('invalid');
        expect(buildNativeRenderState(nested).status).toBe('invalid');
    });

    it('rejects excessive scalar, depth, node count and visible text independently', () => {
        const scalar = documentOf([nativeNode('paragraph', {}, [
            nativeNode('text', { text: 'x'.repeat(NATIVE_RENDER_LIMITS.maxScalarBytes + 1) })
        ])]);

        let deepNode = nativeNode('paragraph');
        for (let index = 0; index < NATIVE_RENDER_LIMITS.maxDepth; index += 1) {
            deepNode = nativeNode('blockquote', {}, [deepNode]);
        }
        const deep = documentOf([deepNode]);
        const numerous = documentOf(Array.from(
            { length: NATIVE_RENDER_LIMITS.maxNodes },
            () => nativeNode('paragraph')
        ));
        const visible = documentOf(Array.from({ length: 33 }, () => nativeNode('paragraph', {}, [
            nativeNode('text', { text: 'x'.repeat(NATIVE_RENDER_LIMITS.maxScalarBytes) })
        ])));

        expect(buildNativeRenderState(scalar).status).toBe('invalid');
        expect(buildNativeRenderState(deep).status).toBe('invalid');
        expect(buildNativeRenderState(numerous).status).toBe('invalid');
        expect(buildNativeRenderState(visible).status).toBe('invalid');
    });

    it('accepts documents exactly at the renderer node and depth limits', () => {
        let deepestAllowed = nativeNode('paragraph');
        for (let index = 0; index < NATIVE_RENDER_LIMITS.maxDepth - 2; index += 1) {
            deepestAllowed = nativeNode('blockquote', {}, [deepestAllowed]);
        }
        const exactDepth = documentOf([deepestAllowed]);
        const exactCount = documentOf(Array.from(
            { length: NATIVE_RENDER_LIMITS.maxNodes - 1 },
            () => nativeNode('paragraph')
        ));

        expect(buildNativeRenderState(exactDepth).status).toBe('ready');
        expect(buildNativeRenderState(exactCount).status).toBe('ready');
    });
});

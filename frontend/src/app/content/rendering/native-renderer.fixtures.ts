import { NativeDocument, NativeJson, NativeNode } from '../native-document';

let nextId = 1;

export function nativeNode(
    type: string,
    attrs: Record<string, NativeJson> = {},
    content: readonly NativeNode[] = [],
    options: { id?: string; version?: number } = {}
): NativeNode {
    const id = options.id ?? `00000000-0000-4000-8000-${(nextId++).toString(16).padStart(12, '0')}`;
    return {
        id,
        type,
        version: options.version ?? 1,
        attrs,
        content
    };
}

export function documentOf(content: readonly NativeNode[], rootVersion = 1): NativeDocument {
    return {
        formatVersion: 1,
        root: nativeNode('doc', {}, content, { version: rootVersion })
    };
}

/** Browser-renderable coverage companion to contracts/content/native-v1/valid/mixed.json. */
export function mixedNativeDocumentFixture(): NativeDocument {
    return documentOf([
        nativeNode('heading', { level: 1, lang: 'ru', dir: 'ltr' }, [
            nativeNode('text', { text: 'Память, письмо и проверяемые знания', marks: ['strong'] })
        ]),
        nativeNode('paragraph', { lang: 'ja', dir: 'auto' }, [
            nativeNode('text', { text: 'Порядок отметок', marks: ['strong', 'em', 'code'] }),
            nativeNode('ruby', { base: '漢字', reading: 'かんじ' })
        ]),
        nativeNode('paragraph', { lang: 'ar', dir: 'rtl' }, [
            nativeNode('text', { text: 'العِلْمُ نورٌ — ' }),
            nativeNode('link', { href: 'https://example.test/source' }, [
                nativeNode('text', { text: 'مصدر آمن' }),
                nativeNode('text', { text: ' ومتابعة', marks: ['em'] })
            ])
        ]),
        nativeNode('paragraph', {}, [
            nativeNode('text', { text: 'Before ' }),
            nativeNode('future_formula', {
                source: '<img src=x onerror=globalThis.pwned=1>',
                engine: 'future',
                href: 'javascript:globalThis.pwned=3'
            }, [], { version: 3 }),
            nativeNode('text', { text: ' after.' })
        ]),
        nativeNode('heading', {
            level: 2,
            futureFlag: { unsafeLooking: '<script>globalThis.pwned=2</script>' }
        }, [nativeNode('text', { text: 'Future heading remains opaque' })], { version: 2 }),
        nativeNode('table', { payload: { nested: true } }, [
            nativeNode('future_row', { position: 1 })
        ], { version: 7 }),
        nativeNode('blockquote', { lang: 'ru', dir: 'auto' }, [
            nativeNode('paragraph', {}, [nativeNode('text', { text: 'Цитата проверяет вложенный блок.' })])
        ]),
        nativeNode('bullet_list', {}, [
            nativeNode('list_item', {}, [
                nativeNode('paragraph', {}, [nativeNode('text', { text: 'Первый пункт' })])
            ])
        ]),
        nativeNode('ordered_list', { order: 7 }, [
            nativeNode('list_item', {}, [
                nativeNode('paragraph', {}, [nativeNode('text', { text: 'Седьмой пункт' })])
            ])
        ]),
        nativeNode('divider')
    ]);
}

import { ChangeDetectionStrategy, Component } from '@angular/core';

import { NativeDocument, NativeJson, NativeNode } from './app/content/native-document';
import { NativeDocumentRendererComponent } from './app/content/rendering/native-document-renderer.component';

let nextNodeId = 1;

function node(type: string, attrs: Record<string, NativeJson> = {}, content: readonly NativeNode[] = [], version = 1): NativeNode {
    return {
        id: `10000000-0000-4000-8000-${(nextNodeId++).toString(16).padStart(12, '0')}`,
        type,
        version,
        attrs,
        content
    };
}

function documentOf(content: readonly NativeNode[]): NativeDocument {
    return { formatVersion: 1, root: node('doc', { lang: 'ru', dir: 'auto' }, content) };
}

const READY_DOCUMENT = documentOf([
    node('heading', { level: 1, lang: 'ru', dir: 'ltr' }, [
        node('text', { text: 'Память, письмо и проверяемые знания', marks: ['strong'] })
    ]),
    node('paragraph', { lang: 'ru', dir: 'ltr' }, [
        node('text', {
            text: 'Мы возвращаемся к сложной мысли не ради повтора, а чтобы увидеть её точнее. '
                + 'Этот длинный русский абзац проверяет спокойный ритм чтения, переносы строк и узкую мобильную колонку.\n'
                + 'Два пробела здесь  сохранены, а сверхдлинное слово '
        }),
        node('text', { text: 'памятьписьмопроверяемоезнание'.repeat(7), marks: ['em'] }),
        node('text', { text: ' не должно расширить страницу.' })
    ]),
    node('paragraph', { lang: 'ja', dir: 'auto' }, [
        node('text', { text: 'Японская запись: ', marks: ['em'] }),
        node('ruby', { base: '漢字', reading: 'かんじ' }),
        node('text', { text: ' — знаки и чтение стоят рядом без добавленного пробела.' })
    ]),
    node('paragraph', { lang: 'ar', dir: 'rtl' }, [
        node('text', { text: 'العِلْمُ نورٌ — ' }),
        node('link', { href: 'https://example.test/source' }, [
            node('text', { text: 'مصدر آمن' }),
            node('text', { text: ' ومتابعة', marks: ['strong'] })
        ])
    ]),
    node('blockquote', { lang: 'ru', dir: 'auto' }, [
        node('paragraph', {}, [
            node('text', { text: '«Хорошая заметка оставляет место для следующего вопроса».', marks: ['em'] })
        ])
    ]),
    node('bullet_list', {}, [
        node('list_item', {}, [node('paragraph', {}, [node('text', { text: 'Смысл сохраняется в обычном тексте.' })])]),
        node('future_list_item', { payload: '<iframe src=javascript:alert(1)>' }, [], 5)
    ]),
    node('ordered_list', { order: 7 }, [
        node('list_item', {}, [node('paragraph', {}, [node('text', { text: 'Нумерация начинается с семи.' })])])
    ]),
    node('paragraph', {}, [
        node('text', { text: 'До ' }),
        node('future_formula', {
            source: '<img src=x onerror=globalThis.pwned=1>',
            href: 'javascript:globalThis.pwned=2'
        }, [], 3),
        node('text', { text: ' после неподдерживаемого встроенного фрагмента.' })
    ]),
    node('table', { privatePayload: { nested: ['never', 'render'] } }, [
        node('future_row', { content: '<script>globalThis.pwned=3</script>' }, [], 8)
    ], 7),
    node('divider')
]);

const INVALID_DOCUMENT = documentOf([
    node('paragraph', {}, [
        node('link', { href: 'javascript:globalThis.pwned=1' }, [node('text', { text: 'unsafe' })])
    ])
]);

@Component({
    selector: 'app-root',
    imports: [NativeDocumentRendererComponent],
    template: `
      <main class="harness-shell">
        <header class="harness-header">
          <p class="harness-kicker">Изолированный component harness · #183</p>
          <h1>{{ failure ? 'Состояние ошибки renderer' : 'Предпросмотр материала' }}</h1>
        </header>
        <app-native-document-renderer [document]="document" />
      </main>
    `,
    styles: `
      :host {
        display: block;
        min-width: 0;
        min-height: 100dvh;
        background: #f4f0e5;
        color: #342e44;
      }

      .harness-shell {
        box-sizing: border-box;
        width: 100%;
        min-height: 100dvh;
        margin: 0;
        padding: clamp(1rem, 4vw, 3rem) clamp(0.75rem, 4vw, 3rem) 4rem;
      }

      .harness-header {
        width: min(72ch, 100%);
        margin: 0 auto clamp(1rem, 3vw, 1.75rem);
      }

      .harness-kicker {
        margin: 0 0 0.45rem;
        color: #625c70;
        font: 600 0.72rem/1.3 system-ui, sans-serif;
        letter-spacing: 0.08em;
        text-transform: uppercase;
      }

      h1 {
        margin: 0;
        color: #281378;
        font: 500 clamp(1.65rem, 1.35rem + 1.5vw, 2.7rem)/1.08 Georgia, 'Times New Roman', serif;
      }

      @media (max-width: 30rem) {
        .harness-shell {
          padding-inline: 0;
        }

        .harness-header {
          box-sizing: border-box;
          padding-inline: 1.125rem;
        }
      }
    `,
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class RendererVisualHarnessComponent {
    protected readonly failure = new URLSearchParams(window.location.search).get('state') === 'invalid';
    protected readonly document = this.failure ? INVALID_DOCUMENT : READY_DOCUMENT;
}

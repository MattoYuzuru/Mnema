import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Small, deliberately limited Markdown dialect for short deck descriptions. */
export function renderDeckDescription(source: string): string {
    const lines = source.replace(/\r\n?/g, '\n').split('\n');
    const parts: string[] = [];
    let list: 'ul' | 'ol' | null = null;
    let paragraph: string[] = [];
    const flushParagraph = () => {
        if (paragraph.length) parts.push(`<p>${paragraph.map(renderInline).join('<br>')}</p>`);
        paragraph = [];
    };
    const closeList = () => {
        if (list) parts.push(`</${list}>`);
        list = null;
    };
    for (const line of lines) {
        const value = line.trim();
        if (!value) { flushParagraph(); closeList(); continue; }
        const bullet = /^[-*+]\s+(.+)$/u.exec(value);
        const numbered = /^\d+\.\s+(.+)$/u.exec(value);
        if (bullet || numbered) {
            flushParagraph();
            const kind = bullet ? 'ul' : 'ol';
            if (list !== kind) { closeList(); parts.push(`<${kind}>`); list = kind; }
            parts.push(`<li>${renderInline(bullet?.[1] ?? numbered?.[1] ?? '')}</li>`);
        } else {
            closeList();
            paragraph.push(value);
        }
    }
    flushParagraph();
    closeList();
    return parts.join('');
}

function renderInline(value: string): string {
    return escapeHtml(value)
        .replace(/\*\*(.+?)\*\*/gu, '<strong>$1</strong>')
        .replace(/__(.+?)__/gu, '<strong>$1</strong>')
        .replace(/~~(.+?)~~/gu, '<del>$1</del>')
        .replace(/(?<!\*)\*([^*]+)\*(?!\*)/gu, '<em>$1</em>')
        .replace(/(?<!_)_([^_]+)_(?!_)/gu, '<em>$1</em>');
}

function escapeHtml(value: string): string {
    return value.replace(/&/gu, '&amp;').replace(/</gu, '&lt;').replace(/>/gu, '&gt;')
        .replace(/"/gu, '&quot;').replace(/'/gu, '&#39;');
}

@Component({
    selector: 'app-deck-description',
    template: '<div class="deck-description-markdown" [innerHTML]="html()"></div>',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeckDescriptionComponent {
    readonly text = input.required<string>();
    readonly html = computed(() => renderDeckDescription(this.text()));
}

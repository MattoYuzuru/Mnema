/** A bounded editorial Markdown subset. Raw HTML, images and embedded content are always ordinary text. */
export interface EventInline {
    readonly kind: 'text' | 'strong' | 'em' | 'code' | 'link';
    readonly text: string;
    readonly href?: string;
}
export interface EventBlock {
    readonly kind: 'paragraph' | 'heading' | 'list' | 'quote' | 'code';
    readonly lines: readonly (readonly EventInline[])[];
}

function safeHref(raw: string): string | null {
    if (raw.startsWith('/') && !raw.startsWith('//') && !/[\\\s]/u.test(raw)
        && [...raw].every(character => character.charCodeAt(0) >= 32)) return raw;
    try {
        const url = new URL(raw);
        return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
    } catch { return null; }
}

export function eventInline(line: string): readonly EventInline[] {
    const pattern = /\[([^\]\n]{1,300})\]\(([^\s()]{1,2048})\)|\*\*([^*\n]+)\*\*|`([^`\n]+)`|\*([^*\n]+)\*/gu;
    const parts: EventInline[] = [];
    let offset = 0;
    for (const match of line.matchAll(pattern)) {
        if (match.index > offset) parts.push({ kind: 'text', text: line.slice(offset, match.index) });
        if (match[1]) {
            const href = safeHref(match[2]);
            parts.push(href === null ? { kind: 'text', text: match[0] } : { kind: 'link', text: match[1], href });
        } else if (match[3]) parts.push({ kind: 'strong', text: match[3] });
        else if (match[4]) parts.push({ kind: 'code', text: match[4] });
        else parts.push({ kind: 'em', text: match[5] });
        offset = match.index + match[0].length;
    }
    if (offset < line.length) parts.push({ kind: 'text', text: line.slice(offset) });
    return parts;
}

export function eventMarkdown(source: string): readonly EventBlock[] {
    const blocks: EventBlock[] = [];
    let paragraph: string[] = [];
    let list: string[] = [];
    let code: string[] | null = null;
    const flush = () => {
        if (paragraph.length) { blocks.push({ kind: 'paragraph', lines: [eventInline(paragraph.join('\n'))] }); paragraph = []; }
        if (list.length) { blocks.push({ kind: 'list', lines: list.map(eventInline) }); list = []; }
    };
    for (const line of source.slice(0, 16000).replace(/\r\n?/gu, '\n').split('\n')) {
        if (line.startsWith('```')) {
            flush();
            if (code === null) code = [];
            else { blocks.push({ kind: 'code', lines: [[{ kind: 'text', text: code.join('\n') }]] }); code = null; }
        } else if (code !== null) code.push(line);
        else if (!line.trim()) flush();
        else if (/^#{1,6} /u.test(line)) { flush(); blocks.push({ kind: 'heading', lines: [eventInline(line.replace(/^#{1,6} /u, ''))] }); }
        else if (/^[-*] /u.test(line)) { if (paragraph.length) flush(); list.push(line.slice(2)); }
        else if (line.startsWith('> ')) { flush(); blocks.push({ kind: 'quote', lines: [eventInline(line.slice(2))] }); }
        else { if (list.length) flush(); paragraph.push(line); }
    }
    if (code !== null) blocks.push({ kind: 'code', lines: [[{ kind: 'text', text: code.join('\n') }]] });
    flush();
    return blocks;
}

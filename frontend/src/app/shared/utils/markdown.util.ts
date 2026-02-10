export function markdownToHtml(markdown: string): string {
    if (!markdown) {
        return '';
    }

    let html = escapeHtml(markdown);

    html = processFencedCodeBlocks(html);
    html = processHeadings(html);
    html = processLists(html);

    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/__(.+?)__/g, '<strong>$1</strong>');
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
    html = html.replace(/(^|[^_])_(?!_)(.+?)_(?!_)/g, '$1<em>$2</em>');
    html = html.replace(/`(.+?)`/g, '<code>$1</code>');
    html = html.replace(/\n/g, '<br>');

    return html;
}

function escapeHtml(text: string): string {
    const map: Record<string, string> = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;'
    };
    return text.replace(/[&<>"']/g, (m) => map[m]);
}

function processFencedCodeBlocks(text: string): string {
    return text.replace(/```([a-z]*)\n([\s\S]*?)```/g, (match, lang, code) => {
        return '<pre><code>' + code.trim() + '</code></pre>';
    });
}

function processHeadings(text: string): string {
    text = text.replace(/^### (.+)$/gm, '<h3>$1</h3>');
    text = text.replace(/^## (.+)$/gm, '<h2>$1</h2>');
    text = text.replace(/^# (.+)$/gm, '<h1>$1</h1>');
    return text;
}

function processLists(text: string): string {
    const lines = text.split('\n');
    const result: string[] = [];
    let inList = false;

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const listMatch = line.match(/^[\-\*] (.+)$/);

        if (listMatch) {
            if (!inList) {
                result.push('<ul>');
                inList = true;
            }
            result.push('<li>' + listMatch[1] + '</li>');
        } else {
            if (inList) {
                result.push('</ul>');
                inList = false;
            }
            result.push(line);
        }
    }

    if (inList) {
        result.push('</ul>');
    }

    return result.join('\n');
}

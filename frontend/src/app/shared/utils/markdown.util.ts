type TokenKind =
    | 'attr-name'
    | 'attr-value'
    | 'boolean'
    | 'comment'
    | 'keyword'
    | 'meta'
    | 'number'
    | 'property'
    | 'string'
    | 'tag'
    | 'type'
    | 'variable';

interface TokenRule {
    kind: TokenKind;
    regex: RegExp;
}

const BLOCK_PLACEHOLDER_PREFIX = '@@MNEMACODEBLOCK';
const INLINE_PLACEHOLDER_PREFIX = '@@MNEMAINLINECODE';
const LANGUAGE_ALIASES: Record<string, string> = {
    cjs: 'javascript',
    cts: 'typescript',
    html: 'html',
    htm: 'html',
    java: 'java',
    js: 'javascript',
    json: 'json',
    jsx: 'javascript',
    mjs: 'javascript',
    sh: 'bash',
    shell: 'bash',
    ts: 'typescript',
    tsx: 'typescript',
    xhtml: 'html',
    xml: 'html',
    zsh: 'bash'
};

const COMMON_RULES: TokenRule[] = [
    createRule('comment', /\/\/[^\n]*/),
    createRule('comment', /\/\*[\s\S]*?\*\//),
    createRule('string', /'(?:\\.|[^'\\])*'/),
    createRule('string', /"(?:\\.|[^"\\])*"/),
    createRule('string', /`(?:\\.|[^`\\])*`/),
    createRule('number', /\b\d+(?:\.\d+)?\b/),
    createRule('boolean', /\b(?:true|false|null|undefined)\b/)
];

const JAVASCRIPT_RULES: TokenRule[] = [
    ...COMMON_RULES,
    createRule('keyword', /\b(?:as|async|await|break|case|catch|class|const|continue|default|delete|do|else|export|extends|finally|for|from|function|if|import|in|instanceof|let|new|of|return|switch|throw|try|typeof|var|void|while|with|yield)\b/),
    createRule('type', /\b(?:any|bigint|boolean|never|number|object|Promise|string|symbol|unknown|void)\b/),
    createRule('variable', /@[A-Za-z_$][\w$]*/)
];

const JAVA_RULES: TokenRule[] = [
    ...COMMON_RULES,
    createRule('meta', /@[A-Za-z_]\w*/),
    createRule('keyword', /\b(?:abstract|assert|break|case|catch|class|continue|default|do|else|enum|extends|final|finally|for|if|implements|import|instanceof|interface|native|new|package|private|protected|public|record|return|sealed|static|super|switch|synchronized|this|throw|throws|transient|try|var|volatile|while)\b/),
    createRule('type', /\b(?:boolean|byte|char|double|float|int|Integer|long|short|String|UUID|void)\b/)
];

const CSS_RULES: TokenRule[] = [
    createRule('comment', /\/\*[\s\S]*?\*\//),
    createRule('meta', /@[a-z-]+/i),
    createRule('string', /'(?:\\.|[^'\\])*'/),
    createRule('string', /"(?:\\.|[^"\\])*"/),
    createRule('number', /\b\d+(?:\.\d+)?(?:px|rem|em|%|vh|vw|ms|s|deg)?\b/),
    createRule('property', /--?[a-z-]+(?=\s*:)/i),
    createRule('keyword', /\b(?:auto|block|flex|grid|inherit|initial|none|relative|absolute|fixed|sticky)\b/)
];

const BASH_RULES: TokenRule[] = [
    createRule('comment', /#[^\n]*/),
    createRule('string', /'(?:\\.|[^'\\])*'/),
    createRule('string', /"(?:\\.|[^"\\])*"/),
    createRule('keyword', /\b(?:case|do|done|elif|else|esac|fi|for|function|if|in|select|then|until|while)\b/),
    createRule('variable', /\$(?:\{[^}]+\}|[A-Za-z_]\w*|[0-9@*#!?$-])/),
    createRule('number', /\b\d+\b/)
];

const JSON_RULES: TokenRule[] = [
    createRule('property', /"(?:\\.|[^"\\])*"(?=\s*:)/),
    createRule('string', /"(?:\\.|[^"\\])*"/),
    createRule('number', /\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/),
    createRule('boolean', /\b(?:true|false|null)\b/)
];

export function markdownToHtml(markdown: string): string {
    if (!markdown) {
        return '';
    }

    const codeBlocks: string[] = [];
    const inlineCode: string[] = [];

    let html = markdown.replace(/```([a-zA-Z0-9_+-]*)[^\n]*\n([\s\S]*?)```/g, (_match, lang, code) => {
        const placeholder = `${BLOCK_PLACEHOLDER_PREFIX}${codeBlocks.length}@@`;
        codeBlocks.push(renderCodeBlock(code, lang));
        return placeholder;
    });

    html = escapeHtml(html);

    html = html.replace(/`([^`\n]+)`/g, (_match, code) => {
        const placeholder = `${INLINE_PLACEHOLDER_PREFIX}${inlineCode.length}@@`;
        inlineCode.push(renderInlineCode(code));
        return placeholder;
    });

    html = processHeadings(html);
    html = processLists(html);
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/__(.+?)__/g, '<strong>$1</strong>');
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
    html = html.replace(/(^|[^_])_(?!_)(.+?)_(?!_)/g, '$1<em>$2</em>');
    html = html.replace(/\n/g, '<br>');

    html = restorePlaceholders(html, INLINE_PLACEHOLDER_PREFIX, inlineCode);
    html = restorePlaceholders(html, BLOCK_PLACEHOLDER_PREFIX, codeBlocks);

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

function renderInlineCode(code: string): string {
    return `<code class="mn-inline-code">${highlightCode(code, detectLanguage(code))}</code>`;
}

function renderCodeBlock(code: string, languageHint: string): string {
    const normalizedLanguage = normalizeLanguage(languageHint) ?? detectLanguage(code);
    const className = normalizedLanguage ? ` mn-code--${normalizedLanguage}` : '';
    const languageLabel = normalizedLanguage ? ` data-language="${escapeHtml(normalizedLanguage)}"` : '';
    return `<pre class="mn-code-block${className}"${languageLabel}><code class="mn-code">${highlightCode(trimFencedCode(code), normalizedLanguage)}</code></pre>`;
}

function highlightCode(code: string, language: string | null): string {
    const normalizedLanguage = normalizeLanguage(language);
    if (normalizedLanguage === 'html') {
        return highlightHtml(code);
    }
    if (normalizedLanguage === 'json') {
        return highlightJson(code);
    }

    const rules = getRules(normalizedLanguage);
    return tokenize(code, rules);
}

function highlightHtml(code: string): string {
    let result = '';
    let lastIndex = 0;
    const tokenPattern = /<!--[\s\S]*?-->|<![^>]+>|<\/?[A-Za-z][^>]*>/g;

    for (const match of code.matchAll(tokenPattern)) {
        const index = match.index ?? 0;
        result += escapeHtml(code.slice(lastIndex, index));
        const token = match[0];
        if (token.startsWith('<!--')) {
            result += wrapToken('comment', token);
        } else if (token.startsWith('<!')) {
            result += wrapToken('meta', token);
        } else {
            result += wrapHtmlTag(token);
        }
        lastIndex = index + token.length;
    }

    result += escapeHtml(code.slice(lastIndex));
    return result;
}

function wrapHtmlTag(tag: string): string {
    const escapedTag = escapeHtml(tag);
    return escapedTag
        .replace(/^(&lt;\/?)([A-Za-z][\w:-]*)/, (_match, opener, name) =>
            `${wrapToken('tag', decodeHtml(opener))}${wrapToken('tag', name)}`
        )
        .replace(/\s+([A-Za-z_:][-A-Za-z0-9_:.]*)(=)/g, (_match, attrName, equals) =>
            ` ${wrapToken('attr-name', attrName)}${escapeHtml(equals)}`
        )
        .replace(/(&quot;[^&]*?&quot;|&#039;[^&]*?&#039;)/g, (value) =>
            wrapToken('attr-value', decodeHtml(value))
        )
        .replace(/(&gt;|\/&gt;)$/, (closing) => wrapToken('tag', decodeHtml(closing)));
}

function highlightJson(code: string): string {
    return tokenize(code, JSON_RULES);
}

function tokenize(code: string, rules: TokenRule[]): string {
    let index = 0;
    let result = '';

    while (index < code.length) {
        let matched = false;

        for (const rule of rules) {
            rule.regex.lastIndex = index;
            const match = rule.regex.exec(code);
            if (match && match.index === index) {
                result += wrapToken(rule.kind, match[0]);
                index += match[0].length;
                matched = true;
                break;
            }
        }

        if (!matched) {
            result += escapeHtml(code[index]);
            index++;
        }
    }

    return result;
}

function getRules(language: string | null): TokenRule[] {
    switch (language) {
        case 'bash':
            return BASH_RULES;
        case 'css':
            return CSS_RULES;
        case 'java':
            return JAVA_RULES;
        case 'javascript':
        case 'typescript':
            return JAVASCRIPT_RULES;
        default:
            return COMMON_RULES;
    }
}

function normalizeLanguage(language: string | null | undefined): string | null {
    const normalized = (language || '').trim().toLowerCase();
    if (!normalized) {
        return null;
    }
    return LANGUAGE_ALIASES[normalized] ?? normalized;
}

function detectLanguage(code: string): string | null {
    const trimmed = code.trim();
    if (!trimmed) {
        return null;
    }
    if ((trimmed.startsWith('{') || trimmed.startsWith('[')) && /"\s*:/.test(trimmed)) {
        return 'json';
    }
    if (/^\s*</.test(trimmed) && /<\/?[A-Za-z]/.test(trimmed)) {
        return 'html';
    }
    if (/(^|\n)\s*(?:public|private|protected|package|import|record|sealed|class)\b/.test(trimmed)) {
        return 'java';
    }
    if (/(^|\n)\s*(?:const|let|import|export|interface|type|async|await)\b/.test(trimmed)) {
        return 'typescript';
    }
    if (/(^|\n)\s*[@.#A-Za-z-][^{\n]*\{/.test(trimmed) && /:\s*[^;]+;/.test(trimmed)) {
        return 'css';
    }
    if (/^\s*(?:#!\/bin\/|echo\b|\$ |\w+=|if \[|for\b|while\b)/m.test(trimmed)) {
        return 'bash';
    }
    return null;
}

function restorePlaceholders(text: string, prefix: string, values: string[]): string {
    let restored = text;
    for (let index = 0; index < values.length; index++) {
        restored = restored.replace(`${prefix}${index}@@`, values[index]);
    }
    return restored;
}

function wrapToken(kind: TokenKind, value: string): string {
    return `<span class="mn-code-token mn-code-token--${kind}">${escapeHtml(value)}</span>`;
}

function createRule(kind: TokenKind, regex: RegExp): TokenRule {
    return {
        kind,
        regex: new RegExp(regex.source, `${regex.flags}y`)
    };
}

function decodeHtml(value: string): string {
    return value
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&#039;/g, "'")
        .replace(/&amp;/g, '&');
}

function trimFencedCode(code: string): string {
    return code.replace(/^\n+/, '').replace(/\n+$/, '');
}

const CARD_ROOT_SELECTOR_PATTERN = /^(?:\.card(?:\d+)?|body|html|:root|:host)(?=$|[.#:[\s>+~])/i;
const SCOPED_AT_RULES = ['@container', '@document', '@layer', '@media', '@supports'];

export function scopeAnkiCss(css: string, scopeSelector: string): string {
    if (!css.trim()) {
        return '';
    }
    return scopeCssRules(css, scopeSelector);
}

function scopeCssRules(css: string, scopeSelector: string): string {
    let result = '';
    let cursor = 0;

    while (cursor < css.length) {
        const openBrace = findNextRuleBrace(css, cursor);
        if (openBrace === -1) {
            result += css.slice(cursor);
            break;
        }
        const statementEnd = findNextStatementSemicolon(css, cursor);
        if (statementEnd !== -1 && statementEnd < openBrace) {
            result += css.slice(cursor, statementEnd + 1);
            cursor = statementEnd + 1;
            continue;
        }

        const preludeStart = findPreludeStart(css, cursor, openBrace);
        result += css.slice(cursor, preludeStart);

        const prelude = css.slice(preludeStart, openBrace).trim();
        const closeBrace = findMatchingBrace(css, openBrace);
        if (closeBrace === -1) {
            result += css.slice(preludeStart);
            break;
        }

        const body = css.slice(openBrace + 1, closeBrace);
        result += scopeRule(prelude, body, scopeSelector);
        cursor = closeBrace + 1;
    }

    return result;
}

function scopeRule(prelude: string, body: string, scopeSelector: string): string {
    if (!prelude) {
        return `{${body}}`;
    }

    if (prelude.startsWith('@')) {
        const normalizedPrelude = prelude.toLowerCase();
        const shouldScopeNestedRules = SCOPED_AT_RULES.some(atRule => normalizedPrelude.startsWith(atRule));
        const scopedBody = shouldScopeNestedRules ? scopeCssRules(body, scopeSelector) : body;
        return `${prelude} {${scopedBody}}`;
    }

    return `${scopeSelectorList(prelude, scopeSelector)} {${body}}`;
}

function scopeSelectorList(selectorList: string, scopeSelectorValue: string): string {
    return splitSelectorList(selectorList)
        .map(selector => scopeSingleSelector(selector, scopeSelectorValue))
        .join(', ');
}

function scopeSingleSelector(selector: string, scopeSelector: string): string {
    const trimmed = selector.trim();
    if (!trimmed || trimmed.startsWith(scopeSelector)) {
        return trimmed;
    }

    const firstCompoundEnd = findFirstCompoundEnd(trimmed);
    const firstCompound = trimmed.slice(0, firstCompoundEnd);
    const rest = trimmed.slice(firstCompoundEnd);

    if (CARD_ROOT_SELECTOR_PATTERN.test(firstCompound)) {
        return `${scopeSelector}${firstCompound.replace(CARD_ROOT_SELECTOR_PATTERN, '')}${rest}`;
    }

    return `${scopeSelector} ${trimmed}`;
}

function splitSelectorList(selectorList: string): string[] {
    const selectors: string[] = [];
    let depth = 0;
    let quote: string | null = null;
    let start = 0;

    for (let i = 0; i < selectorList.length; i++) {
        const ch = selectorList[i];
        const next = selectorList[i + 1];

        if (quote) {
            if (ch === '\\') {
                i++;
            } else if (ch === quote) {
                quote = null;
            }
            continue;
        }

        if (ch === '"' || ch === "'") {
            quote = ch;
        } else if (ch === '(' || ch === '[') {
            depth++;
        } else if ((ch === ')' || ch === ']') && depth > 0) {
            depth--;
        } else if (ch === ',' && depth === 0) {
            selectors.push(selectorList.slice(start, i));
            start = i + 1;
        }
    }

    selectors.push(selectorList.slice(start));
    return selectors;
}

function findFirstCompoundEnd(selector: string): number {
    let depth = 0;
    let quote: string | null = null;

    for (let i = 0; i < selector.length; i++) {
        const ch = selector[i];

        if (quote) {
            if (ch === '\\') {
                i++;
            } else if (ch === quote) {
                quote = null;
            }
            continue;
        }

        if (ch === '"' || ch === "'") {
            quote = ch;
        } else if (ch === '(' || ch === '[') {
            depth++;
        } else if ((ch === ')' || ch === ']') && depth > 0) {
            depth--;
        } else if (depth === 0 && (/\s/.test(ch) || ch === '>' || ch === '+' || ch === '~')) {
            return i;
        }
    }

    return selector.length;
}

function findPreludeStart(css: string, cursor: number, openBrace: number): number {
    let start = cursor;
    while (start < openBrace && /\s/.test(css[start])) {
        start++;
    }
    return start;
}

function findNextRuleBrace(css: string, start: number): number {
    return findNextTopLevelDelimiter(css, start, '{');
}

function findNextStatementSemicolon(css: string, start: number): number {
    return findNextTopLevelDelimiter(css, start, ';');
}

function findNextTopLevelDelimiter(css: string, start: number, delimiter: '{' | ';'): number {
    let quote: string | null = null;
    let comment = false;
    let parenDepth = 0;

    for (let i = start; i < css.length; i++) {
        const ch = css[i];
        const next = css[i + 1];

        if (comment) {
            if (ch === '*' && next === '/') {
                comment = false;
                i++;
            }
            continue;
        }

        if (quote) {
            if (ch === '\\') {
                i++;
            } else if (ch === quote) {
                quote = null;
            }
            continue;
        }

        if (ch === '/' && next === '*') {
            comment = true;
            i++;
        } else if (ch === '"' || ch === "'") {
            quote = ch;
        } else if (ch === '(') {
            parenDepth++;
        } else if (ch === ')' && parenDepth > 0) {
            parenDepth--;
        } else if (ch === delimiter && parenDepth === 0) {
            return i;
        }
    }

    return -1;
}

function findMatchingBrace(css: string, openBrace: number): number {
    let quote: string | null = null;
    let comment = false;
    let depth = 0;

    for (let i = openBrace; i < css.length; i++) {
        const ch = css[i];
        const next = css[i + 1];

        if (comment) {
            if (ch === '*' && next === '/') {
                comment = false;
                i++;
            }
            continue;
        }

        if (quote) {
            if (ch === '\\') {
                i++;
            } else if (ch === quote) {
                quote = null;
            }
            continue;
        }

        if (ch === '/' && next === '*') {
            comment = true;
            i++;
        } else if (ch === '"' || ch === "'") {
            quote = ch;
        } else if (ch === '{') {
            depth++;
        } else if (ch === '}') {
            depth--;
            if (depth === 0) {
                return i;
            }
        }
    }

    return -1;
}

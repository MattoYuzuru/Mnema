/** WCAG 2.x contrast helpers for the styleguide tables. Resolved `--mn-*` colours are read from the live document. */

export type Rgb = readonly [red: number, green: number, blue: number];

/** Parses `#rgb`, `#rrggbb`, `rgb(r g b)` and `rgb(r, g, b)` (alpha ignored); anything else is `null`. */
export function parseCssColor(value: string): Rgb | null {
    const text = value.trim().toLowerCase();
    const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(text);
    if (hex) {
        const digits = hex[1].length === 3 ? [...hex[1]].map(digit => digit + digit).join('') : hex[1];
        return [0, 2, 4].map(index => parseInt(digits.slice(index, index + 2), 16)) as unknown as Rgb;
    }
    const functional = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/.exec(text);
    return functional ? [Number(functional[1]), Number(functional[2]), Number(functional[3])] : null;
}

function channel(value: number): number {
    const share = value / 255;
    return share <= 0.03928 ? share / 12.92 : ((share + 0.055) / 1.055) ** 2.4;
}

export function relativeLuminance(color: Rgb): number {
    return 0.2126 * channel(color[0]) + 0.7152 * channel(color[1]) + 0.0722 * channel(color[2]);
}

/** Contrast ratio of two colours, 1 to 21. */
export function contrastRatio(first: Rgb, second: Rgb): number {
    const [lighter, darker] = [relativeLuminance(first), relativeLuminance(second)].sort((a, b) => b - a);
    return (lighter + 0.05) / (darker + 0.05);
}

/** AA for normal text needs 4.5, AAA 7, a non-text boundary 3. */
export function contrastGrade(ratio: number): 'AAA' | 'AA' | 'AA large' | 'fail' {
    if (ratio >= 7) return 'AAA';
    if (ratio >= 4.5) return 'AA';
    return ratio >= 3 ? 'AA large' : 'fail';
}

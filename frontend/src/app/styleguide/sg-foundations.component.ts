import { ChangeDetectionStrategy, Component, ViewEncapsulation, afterNextRender, computed, input, signal } from '@angular/core';

import { NotificationGlyphComponent } from '../core/notifications/notification-glyph.component';
import { contrastGrade, contrastRatio, parseCssColor } from './color-contrast';
import { PALETTE, SEMANTIC_PAIRS, SPACING_TOKENS } from './styleguide.data';
import { SgSpecimenComponent } from './sg-specimen.component';

/** Tokens whose resolved value is shown next to a swatch or a scale step. */
const SIZE_TOKENS = ['--mn-radius', '--mn-touch-min', '--mn-page-width', '--mn-page-gutter', '--mn-workspace-width'] as const;
const MOTION_TOKENS = ['--mn-wave-duration', '--mn-list-wave-duration'] as const;
const FONT_TOKENS = ['--mn-font-display', '--mn-font-body', '--mn-font-mono'] as const;

/** The «Основы» sections: principles, palette, semantic colours, typography, spacing, radii, motion, icons. */
@Component({
    selector: 'app-sg-foundations',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, NotificationGlyphComponent],
    templateUrl: './sg-foundations.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgFoundationsComponent {
    /** The preview switch of the page: motion demos honour it besides `prefers-reduced-motion`. */
    readonly calm = input(false);

    protected readonly palette = PALETTE;
    protected readonly spacing = SPACING_TOKENS;
    protected readonly sizeTokens = SIZE_TOKENS;
    protected readonly motionTokens = MOTION_TOKENS;
    protected readonly fontTokens = FONT_TOKENS;
    protected readonly resolved = signal<Readonly<Record<string, string>>>({});
    protected readonly inkArrival = signal(0);

    protected readonly pairs = computed(() => SEMANTIC_PAIRS.map(row => {
        const foreground = parseCssColor(this.resolved()[row.foreground] ?? '');
        const background = parseCssColor(this.resolved()[row.background] ?? '');
        const ratio = foreground && background ? contrastRatio(foreground, background) : null;
        return { ...row, ratio, grade: ratio === null ? '—' : contrastGrade(ratio) };
    }));

    constructor() {
        afterNextRender(() => {
            const style = getComputedStyle(document.documentElement);
            const names = [
                ...this.palette.flatMap(group => group.tokens.map(entry => entry.token)),
                ...this.spacing, ...SIZE_TOKENS, ...MOTION_TOKENS, ...FONT_TOKENS
            ];
            this.resolved.set(Object.fromEntries(names.map(name => [name, style.getPropertyValue(name).trim()])));
        });
    }

    protected value(token: string): string { return this.resolved()[token] ?? ''; }

    protected replayInk(): void { this.inkArrival.update(count => count + 1); }
}

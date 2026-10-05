import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The «Новое» mark of a generated exercise that was saved recently and not opened or answered yet (AI-13, #291). It is the shared
 * `.stamp.solid`: text, never colour alone; the border survives forced colors.
 */
@Component({
    selector: 'app-new-badge',
    template: `<span class="stamp solid">Новое</span>`,
    styles: [`:host { display: inline-flex; margin-inline-start: .5rem; vertical-align: middle; }`],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NewBadgeComponent {}

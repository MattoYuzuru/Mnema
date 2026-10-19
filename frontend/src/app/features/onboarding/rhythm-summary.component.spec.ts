import { TestBed } from '@angular/core/testing';

import { Rhythm } from '../../core/onboarding/onboarding.models';
import { RhythmSummaryComponent } from './rhythm-summary.component';

describe('RhythmSummaryComponent (the rhythm on the summary of a session)', () => {
    const WEEK: Rhythm = { weekStart: '2026-10-19', today: '2026-10-23', target: 3, days: ['2026-10-20'], weeksMet: 4 };

    function render(rhythm: Rhythm): HTMLElement {
        const fixture = TestBed.createComponent(RhythmSummaryComponent);
        fixture.componentRef.setInput('rhythm', rhythm);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('says the ordinal of the day by the count of days of the week, and the target with the right form of «день»', () => {
        const cases: [readonly string[], number, string, string][] = [
            [['2026-10-20'], 3, 'Первый день на этой неделе', 'Цель — 3\u00a0дня в неделю'],
            [['2026-10-20', '2026-10-21'], 1, 'Второй день на этой неделе', 'Цель — 1\u00a0день в неделю'],
            [['2026-10-19', '2026-10-20', '2026-10-21'], 5, 'Третий день на этой неделе', 'Цель — 5\u00a0дней в неделю'],
            [['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22'], 2, 'Четвёртый день на этой неделе', 'Цель — 2\u00a0дня в неделю'],
            [['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25'], 7, 'Седьмой день на этой неделе', 'Цель — 7\u00a0дней в неделю']
        ];
        for (const [days, target, heading, line] of cases) {
            const root = render({ ...WEEK, days, target });
            expect(root.querySelector('h3')?.textContent, heading).toBe(heading);
            expect(root.querySelector('.hint')?.textContent, line).toBe(line);
            TestBed.resetTestingModule();
        }
    });

    it('is a named section with the heading, the seven days and the target, and never says the weeks in rhythm', () => {
        const root = render(WEEK);
        const section = root.querySelector('section')!;
        expect(section.getAttribute('aria-labelledby')).toBe(root.querySelector('h3')?.id);
        expect(root.querySelectorAll('app-rhythm-week ol.week > li')).toHaveLength(7);
        expect(root.textContent).not.toContain('Недель в ритме');
        expect(root.textContent).not.toMatch(/\b4\b/u);
    });
});

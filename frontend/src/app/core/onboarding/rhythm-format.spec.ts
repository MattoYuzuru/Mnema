import { addDays, dayOrdinal, daysLabel, isInWeek, isIsoDate, isMonday, weekDates, weekSummary } from './rhythm-format';

describe('rhythm format (the dates and the sentences of the weekly rhythm)', () => {
    it('knows a real calendar date from text that only looks like one', () => {
        expect(['2026-10-19', '2028-02-29', '2026-12-31'].every(isIsoDate)).toBe(true);
        for (const value of ['2026-02-30', '2026-13-01', '2026-10-32', '2026-1-1', '2026-10-19T00:00:00Z', '20261019', '', 'today', 20261019, null]) {
            expect(isIsoDate(value), String(value)).toBe(false);
        }
        expect(isIsoDate('2027-02-29')).toBe(false);
    });

    it('knows Mondays, across the epoch and the leap day', () => {
        expect(['2026-10-19', '2026-10-26', '1970-01-05', '1969-12-29', '2028-02-28'].every(isMonday)).toBe(true);
        for (const value of ['2026-10-18', '2026-10-20', '2026-10-25', '1970-01-01', '1970-01-04', '2026-02-30', 'nope']) expect(isMonday(value), value).toBe(false);
    });

    it('adds days over a month, a year and a leap day, and refuses a date that does not exist', () => {
        expect(addDays('2026-10-19', 6)).toBe('2026-10-25');
        expect(addDays('2026-10-29', 3)).toBe('2026-11-01');
        expect(addDays('2026-12-28', 7)).toBe('2027-01-04');
        expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
        expect(addDays('2026-10-19', -1)).toBe('2026-10-18');
        expect(() => addDays('2026-02-30', 1)).toThrow(RangeError);
    });

    it('lists the seven dates of a week, Monday first, over the end of a year', () => {
        expect(weekDates('2026-10-19')).toEqual(['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25']);
        expect(weekDates('2026-12-28')).toEqual(['2026-12-28', '2026-12-29', '2026-12-30', '2026-12-31', '2027-01-01', '2027-01-02', '2027-01-03']);
    });

    it('tells the days of the week from the days around it', () => {
        expect(isInWeek('2026-10-19', '2026-10-19')).toBe(true);
        expect(isInWeek('2026-10-19', '2026-10-25')).toBe(true);
        expect(isInWeek('2026-10-19', '2026-10-18')).toBe(false);
        expect(isInWeek('2026-10-19', '2026-10-26')).toBe(false);
        expect(isInWeek('2026-10-19', 'nope')).toBe(false);
    });

    it('says «день» in the form the number asks for, with a non-breaking space', () => {
        expect([1, 2, 3, 4, 5, 7, 11, 21].map(daysLabel)).toEqual(['1 день', '2 дня', '3 дня', '4 дня', '5 дней', '7 дней', '11 дней', '21 день']);
    });

    it('names the day of the week the learner is on from the count of days, Первый to Седьмой', () => {
        expect([1, 2, 3, 4, 5, 6, 7].map(dayOrdinal)).toEqual(['Первый', 'Второй', 'Третий', 'Четвёртый', 'Пятый', 'Шестой', 'Седьмой']);
        expect([dayOrdinal(0), dayOrdinal(9)]).toEqual(['Первый', 'Седьмой']);
    });

    it('says one sentence of the week: the days in order with «в», «и» before the last, and a plain line for a week without a session', () => {
        const week = '2026-10-19';
        expect(weekSummary(week, [])).toBe('Эта неделя: занятия ещё не отмечены.');
        expect(weekSummary(week, ['2026-10-19'])).toBe('Эта неделя: занятия в понедельник.');
        expect(weekSummary(week, ['2026-10-19', '2026-10-21'])).toBe('Эта неделя: занятия в понедельник и в среду.');
        expect(weekSummary(week, ['2026-10-20', '2026-10-22', '2026-10-23'])).toBe('Эта неделя: занятия во вторник, в четверг и в пятницу.');
        expect(weekSummary(week, ['2026-10-24', '2026-10-25'])).toBe('Эта неделя: занятия в субботу и в воскресенье.');
        // A date outside the week is not a day of it.
        expect(weekSummary(week, ['2026-10-26'])).toBe('Эта неделя: занятия ещё не отмечены.');
    });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { UsageBudgetComponent } from './usage-budget.component';
import { UsageApiService } from './usage-api.service';
import { UsageProtocolError, UsageSnapshot } from './usage.models';
import { freeUsage, plusUsage } from './usage-test-data';

const NBSP = '\u00a0';

describe('UsageBudgetComponent', () => {
    let api: SpyObj<UsageApiService>;

    beforeEach(() => {
        api = spyObj<UsageApiService>({ load: vi.fn().mockName('UsageApiService.load') });
        TestBed.configureTestingModule({ providers: [{ provide: UsageApiService, useValue: api }] });
    });

    async function render(body: unknown): Promise<HTMLElement> {
        api.load.mockReturnValue(of(body as UsageSnapshot));
        return (await settle()).nativeElement as HTMLElement;
    }

    async function settle(): Promise<ComponentFixture<UsageBudgetComponent>> {
        const fixture = TestBed.createComponent(UsageBudgetComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        return fixture;
    }

    const summary = (root: HTMLElement): string => root.querySelector('app-usage-meter .summary')!.textContent!;

    it('says it is counting while the request is pending', () => {
        api.load.mockReturnValue(new Observable<UsageSnapshot>(() => undefined));
        const fixture = TestBed.createComponent(UsageBudgetComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('[role=status]')?.textContent).toContain('Считаем расход ИИ');
        expect(root.querySelector('app-usage-meter')).toBeNull();
    });

    it('renders a paid plan with the meter text as the source of truth and a hidden bar', async () => {
        const root = await render(plusUsage());
        expect(root.querySelector('.plan')?.textContent).toBe('Тариф Plus');
        expect(root.querySelector('.label')?.textContent).toBe('ИИ в октябре');
        // 42 used + 10 reserved of 360 is 14 %; 308 left is 30 medium materials.
        expect(summary(root)).toBe(`Использовано 14${NBSP}%, из них 3${NBSP}% зарезервировано, хватит на ≈${NBSP}30${NBSP}материалов, обновится 1 ноября.`);
        expect(root.querySelector('.bar')?.getAttribute('aria-hidden')).toBe('true');
        expect(root.querySelector('.reserved')).not.toBeNull();
        expect(root.querySelector('.locked')).toBeNull();
        expect(root.querySelectorAll('.tick')).toHaveLength(0);
        expect(root.querySelector('.fair-use')).toBeNull();
        expect(root.textContent).not.toContain('кредит');
    });

    it('renders Free with the locked remainder, the next unlock and weekly ticks, and the counter above 80 %', async () => {
        const root = await render(freeUsage());
        expect(root.querySelector('.plan')?.textContent).toBe('Тариф Free');
        expect(summary(root)).toBe(`Использовано 16${NBSP}%, хватит на ≈${NBSP}0${NBSP}материалов, ещё 74${NBSP}% откроется 5 октября, обновится 1 ноября.`);
        expect(root.querySelector('.locked')).not.toBeNull();
        expect(root.querySelectorAll('.tick')).toHaveLength(3);
        const items = Array.from(root.querySelectorAll('.fair-use li'));
        expect(items.map(item => item.textContent)).toEqual([
            `Распознавание речи: 52${NBSP}из${NBSP}60${NBSP}минут за месяц, сегодня 3${NBSP}из${NBSP}10`]);
        expect(root.querySelector('.fair-use h3')?.getAttribute('id')).toBe('fair-use-heading');
        expect(root.querySelector('.fair-use')?.getAttribute('aria-labelledby')).toBe('fair-use-heading');
    });

    it('hides the fair-use counters until the server flags a bucket above 80 %', async () => {
        const body = freeUsage();
        body['fairUse']['stt']['warn'] = false;
        expect((await render(body)).querySelector('.fair-use')).toBeNull();
        const both = freeUsage();
        both['fairUse']['assessment'] = { ...both['fairUse']['assessment'], used: 45, warn: true };
        const items = Array.from((await render(both)).querySelectorAll('.fair-use li')).map(item => item.textContent);
        expect(items).toHaveLength(2);
        expect(items[1]).toBe(`Проверка ответов ИИ: 45${NBSP}из${NBSP}50${NBSP}ответов за месяц, сегодня 1${NBSP}из${NBSP}5`);
    });

    it('explains a deferred daily burst in a sentence', async () => {
        const body = plusUsage();
        body['dailyBurst'] = { ...body['dailyBurst'], remainingTodayCredits: 0, deferredUntil: '2026-10-02T21:00:00Z' };
        expect((await render(body)).textContent).toContain('Дневной предел расхода достигнут: оставшаяся работа продолжится 3 октября.');
    });

    it('fails soft with a neutral message and retries on request', async () => {
        api.load.mockReturnValue(throwError(() => new UsageProtocolError('Unexpected shape.')));
        const fixture = await settle();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('[role=status]')?.textContent).toContain('Не удалось узнать расход ИИ. Остальной профиль работает как обычно.');
        expect(root.querySelector('[role=alert]')).toBeNull();
        expect(root.textContent).not.toContain('Unexpected');
        expect(root.querySelector('app-usage-meter')).toBeNull();

        api.load.mockReturnValue(of(plusUsage() as unknown as UsageSnapshot));
        root.querySelector<HTMLButtonElement>('.retry')!.click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(api.load).toHaveBeenCalledTimes(2);
        expect(root.querySelector('.plan')?.textContent).toBe('Тариф Plus');
        expect(root.querySelector('.retry')).toBeNull();
    });
});

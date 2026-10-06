import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NEVER, Subject, of, throwError } from 'rxjs';

import { spyObj, type SpyObj } from '../../../testing/mocks';
import { PromoApiService } from './promo-api.service';
import { PromoRedeemComponent } from './promo-redeem.component';
import { PromoRedemption } from './promo.models';
import { PlansProtocolError } from '../plans/plans.models';

const SUCCESS: PromoRedemption = { type: 'TIER_DAYS', plan: 'PLUS', validUntil: '2026-10-20T09:00:00Z', percent: null,
    message: 'Plus до 20 октября, без автопродления.' };
const problem = (status: number, code: string | null, headers: Record<string, string> = {}) =>
    throwError(() => new HttpErrorResponse({ status, error: code === null ? null : { code }, headers: new HttpHeaders(headers) }));

describe('PromoRedeemComponent', () => {
    let api: SpyObj<PromoApiService>;
    let fixture: ComponentFixture<PromoRedeemComponent>;
    let root: HTMLElement;

    beforeEach(() => {
        api = spyObj<PromoApiService>({ redeem: vi.fn().mockName('PromoApiService.redeem') });
        TestBed.configureTestingModule({ providers: [{ provide: PromoApiService, useValue: api }] });
        fixture = TestBed.createComponent(PromoRedeemComponent);
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
    });

    const input = () => root.querySelector<HTMLInputElement>('input')!;
    const form = () => root.querySelector<HTMLFormElement>('form')!;
    const button = () => root.querySelector<HTMLButtonElement>('button')!;

    async function type(text: string, submit = true): Promise<void> {
        input().value = text;
        input().dispatchEvent(new Event('input'));
        if (submit) form().dispatchEvent(new Event('submit', { cancelable: true }));
        await fixture.whenStable();
        fixture.detectChanges();
    }

    it('is a labelled field that does not autofill or auto-correct and says what the code does', () => {
        expect(root.querySelector('label')?.textContent).toBe('Промокод');
        expect(root.querySelector('label')?.getAttribute('for')).toBe(input().id);
        expect(input().getAttribute('autocomplete')).toBe('off');
        expect(input().getAttribute('autocapitalize')).toBe('characters');
        expect(input().getAttribute('spellcheck')).toBe('false');
        expect(input().getAttribute('maxlength')).toBe('64');
        expect(button().type).toBe('submit');
        expect(button().textContent).toBe('Применить');
        expect(root.querySelector('.hint')?.textContent).toContain('ничего не списывает');
        expect(input().getAttribute('aria-describedby')).toContain(root.querySelector('.hint')!.id);
    });

    it('asks for a code before sending anything', async () => {
        await type('   ');
        expect(api.redeem).not.toHaveBeenCalled();
        expect(root.querySelector('.field-error')?.textContent).toBe('Введите промокод.');
        expect(input().getAttribute('aria-invalid')).toBe('true');
        expect(root.querySelector('[role=alert]')?.id).toBe(input().getAttribute('aria-describedby')!.split(' ')[1]);
    });

    it('redeems on submit with a fresh key, announces the plan and tells the host', async () => {
        api.redeem.mockReturnValue(of(SUCCESS));
        const redeemed = vi.fn();
        fixture.componentRef.instance.redeemed.subscribe(redeemed);
        await type(' plus15 ');

        expect(api.redeem).toHaveBeenCalledWith('plus15', expect.stringMatching(/^[0-9a-f-]{36}$/u));
        expect(root.querySelector('[role=status] .notice.success')?.textContent).toBe('Plus до 20 октября, без автопродления.');
        expect(input().value).toBe('');
        expect(input().getAttribute('aria-invalid')).toBeNull();
        expect(redeemed).toHaveBeenCalledWith(SUCCESS);
    });

    it('says a refusal in one calm sentence, keeps the code, and a new key is used for the next try', async () => {
        api.redeem.mockReturnValue(problem(422, 'PROMO_INVALID'));
        await type('WRONG');
        expect(root.querySelector('.field-error')?.textContent).toContain('не подходит');
        expect(input().value).toBe('WRONG');
        expect(input().getAttribute('aria-invalid')).toBe('true');
        expect(root.querySelector('.notice')).toBeNull();

        await type('WRONG');
        expect(api.redeem.mock.calls[1][1]).not.toBe(api.redeem.mock.calls[0][1]);
    });

    it('names the wait of a rate limit', async () => {
        api.redeem.mockReturnValue(problem(429, 'RATE_LIMITED', { 'Retry-After': '1200' }));
        await type('ANY-CODE');
        expect(root.querySelector('.field-error')?.textContent).toBe('Слишком много попыток. Повторите через 20 минут.');
    });

    it('retries a lost answer with the same key and a different code with a new one', async () => {
        api.redeem.mockReturnValueOnce(problem(0, null)).mockReturnValueOnce(of(SUCCESS));
        await type('PLUS15');
        expect(root.querySelector('.field-error')?.textContent).toContain('Не удалось получить результат');
        expect(root.querySelector('.field-error')?.textContent).not.toContain('Тариф не изменился');
        await type('PLUS15', true);
        expect(api.redeem.mock.calls[1][1]).toBe(api.redeem.mock.calls[0][1]);

        api.redeem.mockReturnValueOnce(problem(503, 'IDENTITY_UNAVAILABLE')).mockReturnValueOnce(of(SUCCESS));
        await type('ONE');
        await type('TWO');
        expect(api.redeem.mock.calls[3][1]).not.toBe(api.redeem.mock.calls[2][1]);
    });

    it('retries an unreadable successful reply and equivalent code spelling with the original key', async () => {
        api.redeem.mockReturnValueOnce(throwError(() => new PlansProtocolError('unexpected wire shape'))).mockReturnValueOnce(of(SUCCESS));
        await type('plus-15');
        expect(root.querySelector('.field-error')?.textContent).toContain('Не удалось получить результат');
        await type('PLUS 15');
        expect(api.redeem.mock.calls[1][1]).toBe(api.redeem.mock.calls[0][1]);
    });

    it('keeps an unknown command key when a different code is tried in between', async () => {
        api.redeem.mockReturnValue(problem(0, null));
        await type('FIRSTCODE');
        await type('SECONDCODE');
        await type('FIRSTCODE');
        expect(api.redeem.mock.calls[2][1]).toBe(api.redeem.mock.calls[0][1]);
        expect(api.redeem.mock.calls[1][1]).not.toBe(api.redeem.mock.calls[0][1]);
    });

    it('does not emit or move focus after the field has been destroyed', async () => {
        const response = new Subject<PromoRedemption>();
        api.redeem.mockReturnValue(response);
        const redeemed = vi.fn();
        fixture.componentRef.instance.redeemed.subscribe(redeemed);
        await type('FIRSTCODE');
        const focus = vi.spyOn(input(), 'focus');
        fixture.destroy();
        response.next(SUCCESS);
        response.complete();
        await Promise.resolve();
        expect(redeemed).not.toHaveBeenCalled();
        expect(focus).not.toHaveBeenCalled();
        expect(response.observed).toBe(false);
    });

    it('waits while a request is pending and does not send twice', async () => {
        api.redeem.mockReturnValue(NEVER);
        await type('PLUS15');
        expect(button().textContent).toBe('Применяем…');
        expect(button().getAttribute('aria-disabled')).toBe('true');
        expect(input().readOnly).toBe(true);
        form().dispatchEvent(new Event('submit', { cancelable: true }));
        expect(api.redeem).toHaveBeenCalledOnce();
    });

    it('fills the field from a prefill without applying it, and clears an old refusal when the reader types', async () => {
        fixture.componentRef.setInput('prefill', 'AUTUMN-26');
        fixture.detectChanges();
        expect(input().value).toBe('AUTUMN-26');
        expect(api.redeem).not.toHaveBeenCalled();

        api.redeem.mockReturnValue(problem(409, 'PROMO_ALREADY_USED'));
        await type('AUTUMN-26');
        expect(root.querySelector('.field-error')?.textContent).toBe('Вы уже использовали этот промокод.');
        await type('X', false);
        expect(root.querySelector('.field-error')).toBeNull();
    });
});

import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PromoPopupComponent } from './promo-popup.component';
import { PromoCampaign } from './promo.models';

const CAMPAIGN: PromoCampaign = { id: 'autumn', title: 'Осенняя скидка', body: 'Plus дешевле до конца октября.', cta: 'Посмотреть тарифы', code: null };

describe('PromoPopupComponent', () => {
    let fixture: ComponentFixture<PromoPopupComponent>;
    let root: HTMLElement;
    const accepted = vi.fn();
    const dismissed = vi.fn();
    const declined = vi.fn();

    async function open(campaign: PromoCampaign = CAMPAIGN): Promise<void> {
        fixture = TestBed.createComponent(PromoPopupComponent);
        fixture.componentRef.setInput('campaign', campaign);
        fixture.componentRef.instance.accepted.subscribe(accepted);
        fixture.componentRef.instance.dismissed.subscribe(dismissed);
        fixture.componentRef.instance.declined.subscribe(declined);
        root = fixture.nativeElement as HTMLElement;
        fixture.detectChanges();
        await fixture.whenStable();
    }

    beforeEach(() => { accepted.mockClear(); dismissed.mockClear(); declined.mockClear(); });

    const dialog = () => root.querySelector('dialog')!;
    const buttons = () => [...root.querySelectorAll<HTMLButtonElement>('button')];

    it('is a modal dialog named by its title and described by its text, focused on the title', async () => {
        await open();
        expect(dialog().hasAttribute('open')).toBe(true);
        expect(dialog().getAttribute('aria-labelledby')).toBe(root.querySelector('h2')!.id);
        expect(dialog().getAttribute('aria-describedby')).toBe(root.querySelector('.promo-popup-text')!.id);
        expect(root.querySelector('h2')?.textContent).toBe('Осенняя скидка');
        expect(root.querySelector('.promo-popup-text')?.textContent).toBe('Plus дешевле до конца октября.');
        expect(document.activeElement).toBe(root.querySelector('h2'));
    });

    it('has three explicit ways out and no checkbox, so nothing is pre-ticked', async () => {
        await open();
        expect(buttons().map(button => button.textContent?.trim())).toEqual(['×', 'Посмотреть тарифы', 'Не сейчас', 'Больше не показывать']);
        expect(buttons()[0].getAttribute('aria-label')).toBe('Закрыть');
        expect(root.querySelector('input')).toBeNull();
        expect(root.querySelectorAll('button.primary')).toHaveLength(1);
        buttons()[1].click();
        buttons()[2].click();
        buttons()[3].click();
        buttons()[0].click();
        expect(accepted).toHaveBeenCalledOnce();
        expect(dismissed).toHaveBeenCalledTimes(2);
        expect(declined).toHaveBeenCalledOnce();
    });

    it('turns Esc into a dismissal instead of closing behind the host', async () => {
        await open();
        const cancel = new Event('cancel', { cancelable: true });
        dialog().dispatchEvent(cancel);
        expect(cancel.defaultPrevented).toBe(true);
        expect(dismissed).toHaveBeenCalledOnce();
    });

    it('closes the dialog when it is taken away, so the platform hands focus back', async () => {
        const trigger = document.createElement('button');
        document.body.append(trigger);
        trigger.focus();
        await open();
        const element = dialog();
        const close = vi.spyOn(element, 'close');
        fixture.destroy();
        expect(close).toHaveBeenCalledOnce();
        trigger.remove();
    });

    it('shows a campaign code as text to type, never as an applied one', async () => {
        await open({ ...CAMPAIGN, code: 'AUTUMN-26' });
        const hint = root.querySelector('.hint')!;
        expect(hint.querySelector('.promo-popup-code')?.textContent).toBe('AUTUMN-26');
        expect(hint.textContent).toContain('не применяется сам');
    });
});

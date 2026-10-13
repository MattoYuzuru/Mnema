import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import contract from '../../../../../contracts/community/recommendations.json';
import { AuthService } from '../../auth.service';
import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
import { InterestSettingsComponent } from './interest-settings.component';
import { RecommendationsApiService } from './recommendations-api.service';
import { InterestProfile, parseInterestProfile } from './recommendations.models';
describe('InterestSettingsComponent', () => {
    let api: { profile: ReturnType<typeof vi.fn>; change: ReturnType<typeof vi.fn> };
    beforeEach(() => {
        api = { profile: vi.fn().mockReturnValue(of({ ...parseInterestProfile(contract.select.response), inferredTopics: [contract.profile.availableTopics[2]] })),
            change: vi.fn().mockReturnValue(of(parseInterestProfile(contract.reset.response))) };
        TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { status: signal('authenticated'), user: signal({ accountId: 'a' }), restore: vi.fn().mockResolvedValue(undefined) } },
            { provide: COMMUNITY_LAUNCH, useValue: true }, { provide: RecommendationsApiService, useValue: api }] });
    });
    it('shows chosen/inferred topics, confirms reset inline and announces completion in a pre-existing region', async () => {
        const fixture = TestBed.createComponent(InterestSettingsComponent); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const live = root.querySelector('[role=status]'); expect(live?.textContent).toBe('');
        expect(root.textContent).toContain('Выбраны вами: Испанский, Английский'); expect(root.textContent).toContain('По вашим действиям: Анатомия');
        const press = (text: string) => [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === text)!.click();
        press('Сбросить интересы'); fixture.detectChanges(); expect(api.change).not.toHaveBeenCalled(); expect(root.textContent).toContain('Новые действия');
        press('Отмена'); fixture.detectChanges(); await fixture.whenStable(); expect(api.change).not.toHaveBeenCalled();
        press('Сбросить интересы'); fixture.detectChanges(); press('Да, сбросить интересы'); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        expect(api.change.mock.calls[0][2]).toBe(true); expect(root.querySelector('[role=status]')).toBe(live); expect(live?.textContent).toContain('Интересы сброшены');
        expect(root.textContent).toContain('Выбраны вами: пока нет'); expect(document.activeElement).toBe(root.querySelector('h2'));
    });
    it('focuses the picker on edit and restores both the draft and edit focus on cancel', async () => {
        const fixture = TestBed.createComponent(InterestSettingsComponent); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Изменить интересы')!;
        button.focus(); button.click(); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        expect(document.activeElement).toBe(root.querySelector('app-interest-picker input'));
        const anatomy = root.querySelector<HTMLInputElement>('input[data-interest-topic="anatomy"]')!;
        anatomy.checked = true; anatomy.dispatchEvent(new Event('change')); fixture.detectChanges();
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Отмена')!.click(); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        const edit = [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Изменить интересы')!;
        expect(document.activeElement).toBe(edit); expect(api.change).not.toHaveBeenCalled();
        edit.click(); fixture.detectChanges(); await fixture.whenStable();
        expect(root.querySelector<HTMLInputElement>('input[data-interest-topic="anatomy"]')?.checked).toBe(false);
    });
    it('disables edit cancellation while saving and after an unknown command outcome', async () => {
        const fixture = TestBed.createComponent(InterestSettingsComponent); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Изменить интересы')!.click(); fixture.detectChanges(); await fixture.whenStable();
        const pending = new Subject<InterestProfile>(); api.change.mockReturnValue(pending);
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Сохранить интересы')!.click(); fixture.detectChanges();
        expect([...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Отмена')?.disabled).toBe(true);
        pending.error(new HttpErrorResponse({ status: 0 })); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        expect([...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Отмена')?.disabled).toBe(true);
    });
    it('edits interests through the shared picker and hides every setting for a disabled server', async () => {
        const fixture = TestBed.createComponent(InterestSettingsComponent); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent === 'Изменить интересы')!.click(); fixture.detectChanges();
        expect(root.querySelector('app-interest-picker')).not.toBeNull();
        [...root.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent === 'Сохранить интересы')!.click(); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
        expect(api.change.mock.calls[0][0].topicIds).toEqual(['spanish','english']);
        fixture.destroy(); api.profile.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
        const second = TestBed.createComponent(InterestSettingsComponent); const store = TestBed.inject((await import('./interest-store.service')).InterestStore); store.reload();
        second.detectChanges(); await second.whenStable(); second.detectChanges(); expect(second.nativeElement.querySelector('section')).toBeNull();
    });
});

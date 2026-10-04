import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DEFAULT_SETTINGS, GenerationSettingsComponent, GenerationSettingsValue } from './generation-settings.component';

describe('GenerationSettingsComponent', () => {
    let fixture: ComponentFixture<GenerationSettingsComponent>;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const checkbox = (label: string): HTMLInputElement =>
        [...root().querySelectorAll<HTMLInputElement>('input[type=checkbox]')].find(input => input.labels?.[0]?.textContent?.trim() === label)!;
    const value = (): GenerationSettingsValue => fixture.componentInstance.value();

    function create(initial: Partial<GenerationSettingsValue> = {}, image = true, audio = true): void {
        fixture = TestBed.createComponent(GenerationSettingsComponent);
        fixture.componentRef.setInput('value', { ...DEFAULT_SETTINGS, ...initial });
        fixture.componentRef.setInput('imageAvailable', image);
        fixture.componentRef.setInput('audioAvailable', audio);
        fixture.detectChanges();
    }

    it('offers the effort as a radio group Авто, Кратко, Средне, Подробно, with a live explanation under it that follows the choice', () => {
        create();
        const radios = [...root().querySelectorAll<HTMLInputElement>('input[type=radio]')];
        expect(radios.map(radio => radio.labels?.[0]?.textContent?.trim())).toEqual(['Авто', 'Кратко', 'Средне', 'Подробно']);
        expect(radios[0]!.checked).toBe(true);
        const hint = root().querySelector<HTMLElement>('[aria-live=polite]')!;
        expect(hint.textContent).toContain('Авто: Мнема выберет объём');
        radios[3]!.click();
        fixture.detectChanges();
        expect(value().effort).toBe('DETAILED');
        expect(hint.textContent).toContain('Подробно:');
    });

    it('keeps the attachments, «Похоже на» and «Сначала показать план» behind «Ещё настройки»', () => {
        create();
        const details = root().querySelector('details')!;
        expect(details.open).toBe(false);
        expect(details.querySelector('summary')?.textContent).toBe('Ещё настройки');
        expect(details.contains(checkbox('Аудио'))).toBe(true);
        expect(details.contains(checkbox('Похоже на: как в колоде'))).toBe(true);
        expect(details.contains(checkbox('Сначала показать план'))).toBe(true);
    });

    it('turns the attachments on and off and shows the nested audio parameters only while audio is on', () => {
        create();
        expect(root().querySelector('fieldset.nested')).toBeNull();
        checkbox('Изображения').click();
        fixture.detectChanges();
        expect(value().imageSearch).toBe(true);
        checkbox('Аудио').click();
        fixture.detectChanges();
        expect(value().audio).toBe(true);
        const nested = root().querySelector('fieldset.nested')!;
        expect(nested.querySelector('legend')?.textContent).toBe('Параметры аудио');
        expect(checkbox('Аудио').getAttribute('aria-controls')).toBe(nested.id);
        const voices = [...nested.querySelectorAll<HTMLInputElement>('input[type=radio]')];
        expect(voices.map(voice => voice.labels?.[0]?.textContent?.trim())).toEqual(['Любой', 'Женский', 'Мужской']);
        voices[1]!.click();
        fixture.detectChanges();
        expect(value().audioVoice).toBe('female');
        checkbox('Аудио').click();
        fixture.detectChanges();
        expect(root().querySelector('fieldset.nested')).toBeNull();
    });

    it('lets the user choose the audio language from the select', () => {
        create({ audio: true });
        const trigger = root().querySelector<HTMLButtonElement>('fieldset.nested [role=combobox]')!;
        expect(trigger.getAttribute('aria-label')).toBe('Язык озвучки: Русский');
        trigger.click();
        fixture.detectChanges();
        const options = [...root().querySelectorAll<HTMLElement>('[role=option]')];
        options.find(option => option.textContent?.trim() === 'Японский')!.click();
        fixture.detectChanges();
        expect(value().audioLang).toBe('ja');
    });

    it('disables an attachment whose capability is off, says why, and never shows it as checked', () => {
        create({ imageSearch: true, audio: true }, false, false);
        expect(checkbox('Изображения').disabled).toBe(true);
        expect(checkbox('Изображения').checked).toBe(false);
        expect(checkbox('Аудио').disabled).toBe(true);
        expect(root().textContent).toContain('Изображения и аудио сейчас недоступны');
        expect(root().querySelector('fieldset.nested')).toBeNull();
        create({}, true, false);
        expect(root().textContent).toContain('Озвучка сейчас недоступна');
        create({}, false, true);
        expect(root().textContent).toContain('Поиск изображений сейчас недоступен');
        create({}, true, true);
        expect(root().textContent).toContain('Мнема добавит их, если выбрано');
    });

    it('toggles «Похоже на: как в колоде» (on by default)', () => {
        create();
        expect(checkbox('Похоже на: как в колоде').checked).toBe(true);
        checkbox('Похоже на: как в колоде').click();
        fixture.detectChanges();
        expect(value().similarToDeck).toBe(false);
    });

    it('offers «Сначала показать план» (off by default), says what it does and puts the plan cost next to it once it is on and known (#295)', () => {
        create();
        const plan = checkbox('Сначала показать план');
        expect(plan.disabled).toBe(false);
        expect(plan.checked).toBe(false);
        expect(root().querySelector(`#${plan.getAttribute('aria-describedby')}`)?.textContent).toContain('План стоит отдельно');
        fixture.componentRef.setInput('planCost', 'План: ≈ 1 % лимита');
        fixture.detectChanges();
        expect(root().textContent).not.toContain('План: ≈ 1 % лимита');
        plan.click();
        fixture.detectChanges();
        expect(value().planFirst).toBe(true);
        expect(root().textContent).toContain('План: ≈ 1 % лимита');
        expect(plan.getAttribute('aria-describedby')).toContain('-cost');
        plan.click();
        fixture.detectChanges();
        expect(value().planFirst).toBe(false);
        expect(root().textContent).not.toContain('План: ≈ 1 % лимита');
    });
});

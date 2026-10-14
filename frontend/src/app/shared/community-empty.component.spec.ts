import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { CommunityEmptyComponent } from './community-empty.component';

@Component({ imports: [CommunityEmptyComponent], template: `<app-community-empty [signedIn]="signedIn()" />` })
class HostComponent { readonly signedIn = signal(false); }

describe('CommunityEmptyComponent', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
    let root: HTMLElement;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        root = fixture.nativeElement as HTMLElement;
    });

    it('says the approved text, in the empty-state of the system, named by its heading', () => {
        const section = root.querySelector('section.empty-state')!;
        expect(section.getAttribute('aria-labelledby')).toBe(section.querySelector('h2')!.id);
        expect(section.querySelector('h2')!.textContent).toBe('Пока здесь пусто.');
        expect(section.querySelector('h2 + p')!.textContent).toBe('Сделайте свою колоду публичной, и она появится здесь первой.');
        expect(section.textContent).not.toMatch(/—|–/u);
    });

    it('explains how to share a deck in three steps behind a native disclosure that starts closed', () => {
        const details = root.querySelector<HTMLDetailsElement>('details.how')!;
        expect(details.open).toBe(false);
        expect(details.querySelector('summary')!.textContent).toBe('Как поделиться колодой');
        const steps = [...details.querySelectorAll('ol > li')].map(step => step.textContent);
        expect(steps).toEqual([
            'Откройте свою колоду.',
            'В разделе «Доступ и публикация» выберите «Публичная».',
            'Пройдите чек-лист: описание, тема и язык, публичный профиль.'
        ]);
    });

    it('says in plain words what a deck needs to be listed, after the steps', () => {
        const rule = root.querySelector('details.how .threshold')!;
        expect(rule.textContent).toBe('В колоде должно быть не меньше 10 материалов, хотя бы одно упражнение и описание.');
        expect(rule.previousElementSibling!.tagName).toBe('OL');
    });

    it('sends a guest to the sign-in page', () => {
        const link = root.querySelector('a.button')!;
        expect(link.textContent).toBe('Войти');
        expect(link.getAttribute('href')).toBe('/login');
    });

    it('sends a signed-in reader to their decks', () => {
        fixture.componentInstance.signedIn.set(true);
        fixture.detectChanges();
        const links = root.querySelectorAll('a.button');
        expect(links).toHaveLength(1);
        expect(links[0].textContent).toBe('Мои колоды');
        expect(links[0].getAttribute('href')).toBe('/decks');
    });

    it('gives each instance its own heading id', () => {
        const other = TestBed.createComponent(HostComponent);
        other.detectChanges();
        expect((other.nativeElement as HTMLElement).querySelector('h2')!.id).not.toBe(root.querySelector('h2')!.id);
    });
});

import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { appRoutes } from '../../app.routes';
import { AiPageComponent } from './ai-page.component';

describe('AiPageComponent', () => {
    async function render(): Promise<HTMLElement> {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const fixture = TestBed.createComponent(AiPageComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        return fixture.nativeElement as HTMLElement;
    }

    it('answers what AI does, where data goes, the limits, how to switch it off and the marks policy', async () => {
        const root = await render();
        expect(root.querySelectorAll('h1')).toHaveLength(1);
        expect(root.querySelector('h1')?.textContent).toBe('Как Mnema использует ИИ');
        const headings = [...root.querySelectorAll('h2')].map(heading => heading.textContent);
        expect(headings).toEqual(['Что делает ИИ', 'Куда уходят данные', 'Лимиты и добросовестное использование', 'Как выключить ИИ',
            'Пометки «создано ИИ»', 'Связаться с нами']);
        const text = root.textContent!;
        for (const word of ['Материалы', 'Упражнения', 'Правки', 'Проверка ответов', 'Голос', 'Картинки', 'Источники']) expect(text).toContain(word);
        expect(text).toContain('DeepSeek');
        expect(text).toContain('OpenRouter');
        expect(text).toContain('Google');
        expect(text).toContain('отдельное согласие на регион обработки');
        expect(text).toContain('фильтр не гарантирует обезличивание');
        expect(text).toContain('в России');
        expect(text).toContain('Мы не ставим на материалы и упражнения метку');
    });

    it('describes supported sources and separates recorded speech consent from text synthesis', async () => {
        const root = await render();
        const text = root.textContent!;
        expect(root.querySelector('#what')?.textContent).toContain('режим «Проверять факты»');
        expect(text).not.toContain('Если вы добавили ссылки или файлы');
        expect(text).toContain('согласие на распознавание записи не управляет озвучкой');
        const withdrawal = root.querySelector<HTMLAnchorElement>('#off a');
        expect(withdrawal?.getAttribute('href')).toBe('/profile#speech-consent');
        expect(root.querySelector('#contact')?.textContent).not.toContain('просьбы по вашим данным');
        expect(root.querySelector('#contact a[target="_blank"]')?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('names every section from its own table of contents', async () => {
        const root = await render();
        const ids = [...root.querySelectorAll('section')].map(section => section.id);
        expect(ids).toEqual(['what', 'data', 'limits', 'off', 'marks', 'contact']);
        for (const section of root.querySelectorAll('section')) {
            expect(section.getAttribute('aria-labelledby')).toBe(section.querySelector('h2')!.id);
        }
        expect(root.querySelectorAll('nav.toc a')).toHaveLength(ids.length);
    });

    it('is a public route', async () => {
        const route = appRoutes.find(candidate => candidate.path === 'ai')!;
        expect(route.canActivate).toBeUndefined();
        expect(await route.loadComponent!()).toBe(AiPageComponent);
    });
});

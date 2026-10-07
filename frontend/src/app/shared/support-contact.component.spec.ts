import { TestBed } from '@angular/core/testing';

import { appConfig } from '../app.config';
import { SUPPORT_CONTACT, telegramContact } from './support-contact';
import { SupportContactComponent } from './support-contact.component';

describe('SupportContactComponent', () => {
    it('links the configured bot with a readable name and decorative glyph', () => {
        TestBed.configureTestingModule({ providers: [{ provide: SUPPORT_CONTACT, useValue: telegramContact('MnemaSupportBot') }] });
        const fixture = TestBed.createComponent(SupportContactComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        const link = root.querySelector('a')!;
        expect(link.getAttribute('href')).toBe('https://t.me/MnemaSupportBot');
        expect(link.textContent).toContain('Написать в Telegram');
        expect(link.textContent).toContain('откроется в новой вкладке');
        expect(link.getAttribute('target')).toBe('_blank');
        expect(link.getAttribute('rel')).toBe('noopener noreferrer');
        expect(link.querySelector('app-telegram-glyph')?.getAttribute('aria-hidden')).toBe('true');
        expect(link.querySelector('svg')?.getAttribute('focusable')).toBe('false');
    });

    it('shows a clear status without a fake link before the bot is configured', () => {
        TestBed.configureTestingModule({ providers: [{ provide: SUPPORT_CONTACT, useValue: null }] });
        const fixture = TestBed.createComponent(SupportContactComponent);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('a')).toBeNull();
        expect(root.textContent).toContain('готовится к запуску');
    });

    it('reads the optional runtime contact through the shared injection token', () => {
        const previous = appConfig.supportTelegramUsername;
        appConfig.supportTelegramUsername = 'MnemaSupportBot';
        try {
            expect(TestBed.inject(SUPPORT_CONTACT)).toEqual({ username: 'MnemaSupportBot', url: 'https://t.me/MnemaSupportBot' });
        } finally {
            appConfig.supportTelegramUsername = previous;
        }
    });
});

describe('telegramContact', () => {
    it('accepts BotFather usernames without changing their display case', () => {
        expect(telegramContact('ABbot')?.url).toBe('https://t.me/ABbot');
        expect(telegramContact(`${'a'.repeat(29)}bot`)?.username).toHaveLength(32);
        expect(telegramContact('Mnema_Support_BOT')?.url).toBe('https://t.me/Mnema_Support_BOT');
    });

    it('rejects malformed, non-bot and unsafe contact values', () => {
        for (const value of [undefined, null, 7, '', 'abot', `${'a'.repeat(30)}bot`, 'MnemaSupport', '@MnemaSupportBot',
            'MnemaSupportBot/other', 'MnemaSupportBot?start=bad', 'MnemaSupportBot\n', 'javascript:alert(1)', '<script>', 'Мнемаbot']) {
            expect(telegramContact(value), String(value)).toBeNull();
        }
    });
});

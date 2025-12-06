import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export type Language = 'en' | 'ru';

interface Translations {
    [key: string]: string;
}

const translations: Record<Language, Translations> = {
    en: {
        'app.name': 'Mnema',
        'nav.myStudy': 'My Study',
        'nav.createDeck': 'Create Deck',
        'nav.login': 'Login',
        'nav.logout': 'Logout',
        'nav.profile': 'Profile',
        'nav.settings': 'Settings',
        'home.studyToday': 'Your Study Today',
        'home.yourDecks': 'Your Decks',
        'home.topPublicDecks': 'Top Public Decks',
        'home.viewAll': 'View all',
        'home.continueLearn': 'Continue Learning',
        'home.cardsDue': 'cards due',
        'home.new': 'new',
        'home.noDecksYet': 'No decks yet',
        'home.noDecksDescription': 'Create your first deck or fork a public deck to get started',
        'home.browsePublicDecks': 'Browse Public Decks',
        'home.noPublicDecks': 'No public decks available',
        'home.noPublicDecksDescription': 'Public decks will appear here once they are published',
        'button.learn': 'Learn',
        'button.browse': 'Browse',
        'button.fork': 'Fork',
        'button.update': 'Update',
        'button.createDeck': 'Create Deck',
        'settings.title': 'Settings',
        'settings.theme': 'Theme',
        'settings.themeDescription': 'Choose your preferred theme',
        'settings.language': 'Language',
        'settings.languageDescription': 'Choose your preferred language',
        'settings.archive': 'Archive',
        'settings.archiveDescription': 'Manage archived decks',
        'settings.dangerZone': 'Danger Zone',
        'settings.dangerZoneDescription': 'Irreversible actions',
        'settings.deleteAccount': 'Delete Account',
        'settings.deleteAccountWarning': 'This will permanently delete your account and all data',
        'theme.light': 'Light',
        'theme.dark': 'Dark',
        'theme.auto': 'Auto',
        'language.english': 'English',
        'language.russian': 'Русский',
    },
    ru: {
        'app.name': 'Mnema',
        'nav.myStudy': 'Моё обучение',
        'nav.createDeck': 'Создать колоду',
        'nav.login': 'Войти',
        'nav.logout': 'Выйти',
        'nav.profile': 'Профиль',
        'nav.settings': 'Настройки',
        'home.studyToday': 'Ваше обучение сегодня',
        'home.yourDecks': 'Ваши колоды',
        'home.topPublicDecks': 'Топ публичных колод',
        'home.viewAll': 'Показать все',
        'home.continueLearn': 'Продолжить обучение',
        'home.cardsDue': 'карт к повторению',
        'home.new': 'новых',
        'home.noDecksYet': 'Пока нет колод',
        'home.noDecksDescription': 'Создайте свою первую колоду или форкните публичную колоду, чтобы начать',
        'home.browsePublicDecks': 'Просмотреть публичные колоды',
        'home.noPublicDecks': 'Нет доступных публичных колод',
        'home.noPublicDecksDescription': 'Публичные колоды появятся здесь после публикации',
        'button.learn': 'Учить',
        'button.browse': 'Просмотр',
        'button.fork': 'Форкнуть',
        'button.update': 'Обновить',
        'button.createDeck': 'Создать колоду',
        'settings.title': 'Настройки',
        'settings.theme': 'Тема',
        'settings.themeDescription': 'Выберите предпочитаемую тему',
        'settings.language': 'Язык',
        'settings.languageDescription': 'Выберите предпочитаемый язык',
        'settings.archive': 'Архив',
        'settings.archiveDescription': 'Управление архивированными колодами',
        'settings.dangerZone': 'Опасная зона',
        'settings.dangerZoneDescription': 'Необратимые действия',
        'settings.deleteAccount': 'Удалить аккаунт',
        'settings.deleteAccountWarning': 'Это навсегда удалит ваш аккаунт и все данные',
        'theme.light': 'Светлая',
        'theme.dark': 'Тёмная',
        'theme.auto': 'Авто',
        'language.english': 'English',
        'language.russian': 'Русский',
    }
};

@Injectable({ providedIn: 'root' })
export class I18nService {
    private readonly storageKey = 'mnema_language';
    private _languageSubject: BehaviorSubject<Language>;

    currentLanguage$: Observable<Language>;

    constructor() {
        const saved = localStorage.getItem(this.storageKey) as Language | null;
        const initial: Language = (saved === 'en' || saved === 'ru') ? saved : 'en';
        this._languageSubject = new BehaviorSubject<Language>(initial);
        this.currentLanguage$ = this._languageSubject.asObservable();
    }

    get currentLanguage(): Language {
        return this._languageSubject.value;
    }

    setLanguage(lang: Language): void {
        this._languageSubject.next(lang);
        localStorage.setItem(this.storageKey, lang);
    }

    translate(key: string): string {
        const lang = this.currentLanguage;
        return translations[lang][key] || key;
    }

    t(key: string): string {
        return this.translate(key);
    }
}

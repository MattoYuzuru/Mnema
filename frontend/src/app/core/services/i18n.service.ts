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
        'button.fork': 'Fork Deck',
        'button.update': 'Update',
        'button.createDeck': 'Create Deck',
        'publicDecks.title': 'Public Decks',
        'publicDecks.subtitle': 'Discover and fork community-created flashcard decks',
        'publicDecks.searchPlaceholder': 'Search public decks...',
        'publicDecks.noArchivedDecks': 'No archived decks',
        'cardBrowser.cards': 'cards',
        'cardBrowser.list': 'List',
        'cardBrowser.cardsView': 'Cards',
        'cardBrowser.frontPreview': 'Front Preview',
        'cardBrowser.tags': 'Tags',
        'cardBrowser.previous': '← Previous',
        'cardBrowser.next': 'Next →',
        'cardBrowser.clickToFlip': 'Click card to flip',
        'nav.privacy': 'Privacy',
        'nav.terms': 'Terms',
        'terms.title': 'Terms of Service',
        'terms.lastUpdated': 'Last updated: December 2025',
        'terms.acceptance': 'Acceptance of Terms',
        'terms.acceptanceText': 'By accessing and using Mnema, you accept and agree to be bound by the terms and provisions of this agreement.',
        'terms.useLicense': 'Use License',
        'terms.useLicenseText': 'Permission is granted to temporarily use Mnema for personal, non-commercial educational purposes. This is the grant of a license, not a transfer of title.',
        'terms.userContent': 'User Content',
        'terms.userContentText': 'You retain all rights to the flashcard content you create. By making decks public, you grant other users the right to fork and use your content for their personal learning.',
        'terms.prohibited': 'Prohibited Uses',
        'terms.prohibitedText': 'You may not use Mnema for any illegal purpose or to violate any laws. You may not attempt to gain unauthorized access to any portion of the service.',
        'terms.disclaimer': 'Disclaimer',
        'terms.disclaimerText': 'Mnema is provided "as is" without any representations or warranties. We do not guarantee that the service will be uninterrupted or error-free.',
        'terms.liability': 'Limitation of Liability',
        'terms.liabilityText': 'In no event shall Mnema be liable for any damages arising out of the use or inability to use the service.',
        'terms.changes': 'Changes to Terms',
        'terms.changesText': 'We reserve the right to modify these terms at any time. Continued use of the service after changes constitutes acceptance of the new terms.',
        'terms.contact': 'Contact',
        'terms.contactText': 'For questions about these Terms of Service, please contact us through our GitHub repository.',
        'privacy.title': 'Privacy Policy',
        'privacy.lastUpdated': 'Last updated: December 2025',
        'privacy.infoCollect': 'Information We Collect',
        'privacy.infoCollectText': 'When you use Mnema, we collect information that you provide directly to us, including your email address, username, and the flashcard decks and content you create.',
        'privacy.infoUse': 'How We Use Your Information',
        'privacy.infoUseText': 'We use the information we collect to provide, maintain, and improve our services, including to process your flashcard study sessions and track your learning progress.',
        'privacy.infoSharing': 'Information Sharing',
        'privacy.infoSharingText': 'We do not sell or share your personal information with third parties except as necessary to provide our services or as required by law.',
        'privacy.dataSecurity': 'Data Security',
        'privacy.dataSecurityText': 'We implement appropriate security measures to protect your personal information against unauthorized access, alteration, or destruction.',
        'privacy.yourRights': 'Your Rights',
        'privacy.yourRightsText': 'You have the right to access, update, or delete your personal information at any time through your account settings.',
        'privacy.contact': 'Contact Us',
        'privacy.contactText': 'If you have any questions about this Privacy Policy, please contact us through our GitHub repository.',
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
        'settings.restore': 'Restore',
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
        'button.fork': 'Форкнуть колоду',
        'button.update': 'Обновить',
        'button.createDeck': 'Создать колоду',
        'publicDecks.title': 'Публичные колоды',
        'publicDecks.subtitle': 'Открывайте и форкайте колоды карточек, созданные сообществом',
        'publicDecks.searchPlaceholder': 'Поиск публичных колод...',
        'publicDecks.noArchivedDecks': 'Нет архивированных колод',
        'cardBrowser.cards': 'карт',
        'cardBrowser.list': 'Список',
        'cardBrowser.cardsView': 'Карточки',
        'cardBrowser.frontPreview': 'Предпросмотр лицевой стороны',
        'cardBrowser.tags': 'Теги',
        'cardBrowser.previous': '← Предыдущая',
        'cardBrowser.next': 'Следующая →',
        'cardBrowser.clickToFlip': 'Нажмите на карточку, чтобы перевернуть',
        'nav.privacy': 'Конфиденциальность',
        'nav.terms': 'Условия',
        'terms.title': 'Условия использования',
        'terms.lastUpdated': 'Последнее обновление: декабрь 2025',
        'terms.acceptance': 'Принятие условий',
        'terms.acceptanceText': 'Получая доступ и используя Mnema, вы принимаете и соглашаетесь соблюдать условия и положения настоящего соглашения.',
        'terms.useLicense': 'Лицензия на использование',
        'terms.useLicenseText': 'Разрешается временно использовать Mnema в личных некоммерческих образовательных целях. Это предоставление лицензии, а не передача права собственности.',
        'terms.userContent': 'Пользовательский контент',
        'terms.userContentText': 'Вы сохраняете все права на созданный вами контент карточек. Делая колоды публичными, вы предоставляете другим пользователям право форкать и использовать ваш контент для личного обучения.',
        'terms.prohibited': 'Запрещенное использование',
        'terms.prohibitedText': 'Вы не можете использовать Mnema в незаконных целях или для нарушения каких-либо законов. Вы не можете пытаться получить несанкционированный доступ к какой-либо части сервиса.',
        'terms.disclaimer': 'Отказ от ответственности',
        'terms.disclaimerText': 'Mnema предоставляется "как есть" без каких-либо заявлений или гарантий. Мы не гарантируем, что сервис будет бесперебойным или безошибочным.',
        'terms.liability': 'Ограничение ответственности',
        'terms.liabilityText': 'Ни при каких обстоятельствах Mnema не несет ответственности за какие-либо убытки, возникающие в результате использования или невозможности использования сервиса.',
        'terms.changes': 'Изменения условий',
        'terms.changesText': 'Мы оставляем за собой право изменять эти условия в любое время. Продолжение использования сервиса после изменений означает принятие новых условий.',
        'terms.contact': 'Контакты',
        'terms.contactText': 'По вопросам об этих Условиях использования, пожалуйста, свяжитесь с нами через наш репозиторий на GitHub.',
        'privacy.title': 'Политика конфиденциальности',
        'privacy.lastUpdated': 'Последнее обновление: декабрь 2025',
        'privacy.infoCollect': 'Собираемая информация',
        'privacy.infoCollectText': 'Когда вы используете Mnema, мы собираем информацию, которую вы предоставляете нам напрямую, включая ваш адрес электронной почты, имя пользователя и созданные вами колоды и контент карточек.',
        'privacy.infoUse': 'Как мы используем вашу информацию',
        'privacy.infoUseText': 'Мы используем собранную информацию для предоставления, поддержки и улучшения наших услуг, включая обработку ваших учебных сессий с карточками и отслеживание прогресса обучения.',
        'privacy.infoSharing': 'Обмен информацией',
        'privacy.infoSharingText': 'Мы не продаем и не передаем вашу личную информацию третьим лицам, за исключением случаев, необходимых для предоставления наших услуг или требуемых законом.',
        'privacy.dataSecurity': 'Безопасность данных',
        'privacy.dataSecurityText': 'Мы применяем соответствующие меры безопасности для защиты вашей личной информации от несанкционированного доступа, изменения или уничтожения.',
        'privacy.yourRights': 'Ваши права',
        'privacy.yourRightsText': 'Вы имеете право в любое время получать доступ, обновлять или удалять вашу личную информацию через настройки аккаунта.',
        'privacy.contact': 'Связаться с нами',
        'privacy.contactText': 'Если у вас есть вопросы об этой Политике конфиденциальности, пожалуйста, свяжитесь с нами через наш репозиторий на GitHub.',
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
        'settings.restore': 'Восстановить',
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

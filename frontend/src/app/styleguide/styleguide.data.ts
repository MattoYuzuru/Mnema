/** Static content of the styleguide: the section map and the token lists. Values are never copied here: swatches read the live `--mn-*`. */

export interface SectionLink { readonly id: string; readonly label: string; }
export interface SectionGroup { readonly title: string; readonly sections: readonly SectionLink[]; }

export const SECTION_GROUPS: readonly SectionGroup[] = [
    {
        title: 'Основы',
        sections: [
            { id: 'principles', label: 'Принципы' },
            { id: 'palette', label: 'Палитра' },
            { id: 'semantic', label: 'Семантические цвета' },
            { id: 'typography', label: 'Типографика' },
            { id: 'spacing', label: 'Сетка и отступы' },
            { id: 'radii', label: 'Радиусы и поверхности' },
            { id: 'motion', label: 'Движение' },
            { id: 'icons', label: 'Иконки' }
        ]
    },
    { title: 'Фирменные приёмы', sections: [{ id: 'signature', label: 'Фирменные приёмы' }] },
    {
        title: 'Компоненты',
        sections: [
            { id: 'buttons', label: 'Кнопки' },
            { id: 'fields', label: 'Поля ввода' },
            { id: 'choice', label: 'Выбор' },
            { id: 'menus', label: 'Меню и окна' },
            { id: 'tabs', label: 'Вкладки и пейджер' },
            { id: 'status', label: 'Статусы и ход' },
            { id: 'feedback', label: 'Обратная связь' },
            { id: 'cards', label: 'Карточки и области' }
        ]
    },
    { title: 'Сборка', sections: [{ id: 'screen', label: 'Пример экрана' }] }
];

export interface ColorToken { readonly token: string; readonly role: string; }

/** Every colour token of `src/theme/tokens.css`, grouped by what it paints. */
export const PALETTE: readonly { readonly title: string; readonly tokens: readonly ColorToken[] }[] = [
    {
        title: 'Бумага',
        tokens: [
            { token: '--mn-paper', role: 'Фон страницы' },
            { token: '--mn-sheet', role: 'Лист: поля, карточки, редактор' },
            { token: '--mn-soft', role: 'Выбранное, уведомление, заливка волны' }
        ]
    },
    {
        title: 'Чернила',
        tokens: [
            { token: '--mn-ink', role: 'Заголовки, действия, линии, фокус' },
            { token: '--mn-on-ink', role: 'Текст на чернильной заливке' },
            { token: '--mn-body', role: 'Основной текст' },
            { token: '--mn-muted', role: 'Пояснения; не снижать opacity' },
            { token: '--mn-hint', role: 'Хвост «змейки» в generate-cta' }
        ]
    },
    {
        title: 'Линии',
        tokens: [
            { token: '--mn-rule', role: 'Тонкое разделение; не единственный признак интерактивности' },
            { token: '--mn-field-border', role: 'Граница поля ввода (3:1 к листу)' },
            { token: '--mn-focus', role: 'Контур фокуса' },
            { token: '--mn-selection', role: 'Выделение текста' }
        ]
    },
    {
        title: 'Смысл',
        tokens: [
            { token: '--mn-positive', role: 'Успех, «сохранено»' },
            { token: '--mn-danger', role: 'Ошибка, необратимое действие' },
            { token: '--mn-caution', role: 'Внимание, конфликт версий' }
        ]
    }
];

export interface SemanticRow { readonly role: string; readonly foreground: string; readonly background: string; readonly where: string; }

/** Text/surface pairs the product relies on; the ratio is computed live from the resolved tokens. */
export const SEMANTIC_PAIRS: readonly SemanticRow[] = [
    { role: 'Основной текст', foreground: '--mn-body', background: '--mn-paper', where: 'Страница' },
    { role: 'Текст на листе', foreground: '--mn-body', background: '--mn-sheet', where: 'Карточки, поля, редактор' },
    { role: 'Заголовок и действие', foreground: '--mn-ink', background: '--mn-sheet', where: 'Кнопки-контуры, ссылки, h1–h4' },
    { role: 'Пояснение', foreground: '--mn-muted', background: '--mn-sheet', where: '.hint, подписи' },
    { role: 'Основная кнопка', foreground: '--mn-on-ink', background: '--mn-ink', where: '.button.primary, .stamp.solid' },
    { role: 'Ошибка', foreground: '--mn-danger', background: '--mn-sheet', where: '.field-error, .notice.error' },
    { role: 'Успех', foreground: '--mn-positive', background: '--mn-sheet', where: '.notice.success' },
    { role: 'Внимание', foreground: '--mn-caution', background: '--mn-sheet', where: '.notice.warning' },
    { role: 'Граница поля', foreground: '--mn-field-border', background: '--mn-sheet', where: 'input, textarea (нетекстовый порог 3:1)' }
];

/** The spacing and size steps of `src/theme/tokens.css`. */
export const SPACING_TOKENS: readonly string[] = [
    '--mn-space-1', '--mn-space-2', '--mn-space-3', '--mn-space-4', '--mn-space-5', '--mn-space-6', '--mn-space-7', '--mn-space-8'
];

export const BUTTON_USAGE = `<button type="button" class="button primary">Сохранить</button>
<button type="button" class="button">Отмена</button>
<button type="button" class="button quiet">Закрыть</button>
<button type="button" class="button small" aria-label="Убрать вариант 2">Убрать</button>
<a class="button primary" routerLink="/decks">Мои колоды →</a>
<button type="button" class="button" aria-disabled="true">Недоступно</button>`;

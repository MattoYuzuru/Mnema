-- Схема
CREATE SCHEMA IF NOT EXISTS app_core;

-- =========================
-- ENUM-типы
-- =========================

-- Языки колод
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'language_tag') THEN
            CREATE TYPE language_tag AS ENUM ('ru', 'en');
        END IF;
    END
$$ LANGUAGE plpgsql;

-- Типы полей шаблона карты
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'card_field_type') THEN
            CREATE TYPE card_field_type AS ENUM (
                'text',        -- простой текст
                'rich_text',   -- форматированный текст / markdown
                'cloze',       -- cloze-deletion
                'image',       -- ссылка на картинку
                'audio',       -- ссылка на аудио
                'video',       -- ссылка на видео
                'tags',        -- список тегов
                'boolean'      -- чекбокс / флаг
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

-- Источник ревью (откуда юзер отвечал на карточку)
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'review_source') THEN
            CREATE TYPE review_source AS ENUM (
                'web',
                'mobile',
                'api',
                'import',
                'other'
                );
        END IF;
    END
$$ LANGUAGE plpgsql;

-- =========================
-- Таблицы
-- =========================

-- Публичные колоды (версии колод, которыми можно шариться)
CREATE TABLE IF NOT EXISTS app_core.public_decks
(
    deck_id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    -- Идентификатор колоды (общий для всех её версий)

    version          INT          NOT NULL DEFAULT 1,
    -- Номер версии колоды (1, 2, 3...). Позволяет хранить несколько версий одной колоды.

    author_id        UUID         NOT NULL,
    -- Автор колоды (user_id из auth/users сервиса)

    name             TEXT         NOT NULL,
    -- Отображаемое название колоды

    description      TEXT,
    -- Описание колоды

    template_id      UUID         NOT NULL,
    -- Используемый шаблон карт (FK -> card_templates)

    is_public        BOOLEAN      NOT NULL DEFAULT false,
    -- Можно ли вообще открыть колоду по прямой ссылке (true/false)

    is_listed        BOOLEAN      NOT NULL DEFAULT true,
    -- Показывается ли колода в общем каталоге/поиске

    language_code    language_tag NOT NULL DEFAULT 'ru',
    -- Язык контента колоды (ru/en, можно расширять enum)

    tags             TEXT[],
    -- Свободные теги для поиска/фильтрации (["N5", "verbs"])

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Время создания записи (первой версии)

    updated_at       TIMESTAMPTZ,
    -- Время последнего обновления метаданных этой версии

    published_at     TIMESTAMPTZ,
    -- Когда конкретная версия была опубликована

    forked_from_deck UUID,
    -- deck_id исходной колоды, если это форк (без указания версии)

    PRIMARY KEY (deck_id, version)
);

COMMENT ON TABLE  app_core.public_decks                      IS 'Иммутабельные версии публичных колод, доступных для шаринга.';
COMMENT ON COLUMN app_core.public_decks.deck_id              IS 'Идентификатор колоды (общий для всех версий одной логической колоды).';
COMMENT ON COLUMN app_core.public_decks.version              IS 'Номер версии колоды (1,2,3...). Вместе с deck_id образует PK.';
COMMENT ON COLUMN app_core.public_decks.author_id            IS 'Автор (user_id) колоды.';
COMMENT ON COLUMN app_core.public_decks.name                 IS 'Название колоды, отображаемое в UI.';
COMMENT ON COLUMN app_core.public_decks.description          IS 'Описание колоды.';
COMMENT ON COLUMN app_core.public_decks.template_id          IS 'Ссылка на шаблон карт (card_templates.template_id).';
COMMENT ON COLUMN app_core.public_decks.is_public            IS 'Флаг: true, если колоду можно открыть по прямой ссылке.';
COMMENT ON COLUMN app_core.public_decks.is_listed            IS 'Флаг: true, если колода отображается в общем каталоге.';
COMMENT ON COLUMN app_core.public_decks.language_code        IS 'Язык колоды (ENUM language_tag).';
COMMENT ON COLUMN app_core.public_decks.tags                 IS 'Теги колоды, используются для фильтрации и поиска.';
COMMENT ON COLUMN app_core.public_decks.created_at           IS 'Дата и время создания записи колоды.';
COMMENT ON COLUMN app_core.public_decks.updated_at           IS 'Дата и время последнего изменения записи этой версии.';
COMMENT ON COLUMN app_core.public_decks.published_at         IS 'Дата и время публикации этой версии колоды.';
COMMENT ON COLUMN app_core.public_decks.forked_from_deck     IS 'deck_id исходной колоды, если это форк. Версия не фиксируется.';


-- Публичные карты внутри версий колод
CREATE TABLE IF NOT EXISTS app_core.public_cards
(
    deck_id      UUID      NOT NULL,
    -- Идентификатор колоды, к которой принадлежит карта

    deck_version INT       NOT NULL DEFAULT 1,
    -- Версия колоды, в рамках которой существует эта карта

    card_id      UUID      NOT NULL DEFAULT gen_random_uuid(),
    -- Идентификатор карты внутри публичной части

    content      JSONB     NOT NULL,
    -- Контент карты по шаблону (fields/media/meta и т.д.)

    order_index  INT,
    -- Порядок карты в колоде (для браузера и дефолтного порядка первой сессии)

    tags         TEXT[],
    -- Теги конкретной карты (часть речи, уровень и т.д.)

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Время создания карты

    updated_at   TIMESTAMPTZ,
    -- Время последнего обновления контента карты

    is_active    BOOLEAN   NOT NULL DEFAULT true,
    -- Флаг активности карты (можно скрыть карту в новой версии, но не удалять физически)

    checksum     TEXT,
    -- Хэш контента карты (для синхронизации/выявления изменений)

    PRIMARY KEY (deck_id, deck_version, card_id)
);

COMMENT ON TABLE  app_core.public_cards                      IS 'Карты внутри конкретных версий публичных колод.';
COMMENT ON COLUMN app_core.public_cards.deck_id              IS 'Идентификатор колоды, которой принадлежит карта.';
COMMENT ON COLUMN app_core.public_cards.deck_version         IS 'Номер версии колоды, в которой определена эта карта.';
COMMENT ON COLUMN app_core.public_cards.card_id              IS 'Идентификатор карты внутри колоды и версии.';
COMMENT ON COLUMN app_core.public_cards.content              IS 'JSONB-контент карты по шаблону (fields/media/meta).';
COMMENT ON COLUMN app_core.public_cards.order_index          IS 'Порядковый номер карты внутри колоды.';
COMMENT ON COLUMN app_core.public_cards.tags                 IS 'Произвольные теги карты.';
COMMENT ON COLUMN app_core.public_cards.created_at           IS 'Дата и время создания карты.';
COMMENT ON COLUMN app_core.public_cards.updated_at           IS 'Дата и время последнего обновления карты.';
COMMENT ON COLUMN app_core.public_cards.is_active            IS 'Флаг активности карты (soft-hide для новых версий).';
COMMENT ON COLUMN app_core.public_cards.checksum             IS 'Хэш содержимого карты (для сравнения и синка).';


-- Подписки пользователя на публичные колоды / локальные колоды пользователя
CREATE TABLE IF NOT EXISTS app_core.user_decks
(
    user_deck_id        UUID        NOT NULL DEFAULT gen_random_uuid(),
    -- Идентификатор пользовательской колоды/подписки

    user_id             UUID        NOT NULL,
    -- Пользователь, владелец этой колоды/подписки

    public_deck_id      UUID,
    -- deck_id публичной колоды (NULL, если чисто локальная колода)

    subscribed_version  INT,
    -- Версия публичной колоды, на которую изначально подписались

    current_version     INT,
    -- Версия публичной колоды, которая сейчас используется (после обновлений)

    auto_update         BOOLEAN     NOT NULL DEFAULT true,
    -- Флаг: автоматически ли подтягивать новые версии публичной колоды

    algorithm_id        TEXT,
    -- Идентификатор алгоритма SRS для этой колоды (FK -> sr_algorithms.algorithm_id)

    algorithm_params    JSONB,
    -- Переопределённая конфигурация алгоритма под эту колоду

    display_name        TEXT,
    -- Локальное название колоды, которое видит пользователь

    display_description TEXT,
    -- Локальное описание колоды

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Время создания пользовательской колоды/подписки

    last_synced_at      TIMESTAMPTZ,
    -- Время последней синхронизации с публичной колодой

    is_archived         BOOLEAN     NOT NULL DEFAULT false,
    -- Флаг архивации колоды пользователем

    PRIMARY KEY (user_deck_id)
);

COMMENT ON TABLE  app_core.user_decks                             IS 'Пользовательские колоды: подписки на публичные и локальные колоды.';
COMMENT ON COLUMN app_core.user_decks.user_deck_id                IS 'Идентификатор пользовательской колоды/подписки.';
COMMENT ON COLUMN app_core.user_decks.user_id                     IS 'Пользователь, владелец колоды.';
COMMENT ON COLUMN app_core.user_decks.public_deck_id              IS 'deck_id публичной колоды (NULL для локальных колод).';
COMMENT ON COLUMN app_core.user_decks.subscribed_version          IS 'Версия публичной колоды, на которую пользователю подписался изначально.';
COMMENT ON COLUMN app_core.user_decks.current_version             IS 'Текущая версия публичной колоды, которая сейчас используется.';
COMMENT ON COLUMN app_core.user_decks.auto_update                 IS 'Флаг: при true колода автоматически обновляется до новых версий.';
COMMENT ON COLUMN app_core.user_decks.algorithm_id                IS 'Выбранный алгоритм SRS для этой колоды.';
COMMENT ON COLUMN app_core.user_decks.algorithm_params            IS 'Конфигурация алгоритма SRS для этой колоды (JSONB).';
COMMENT ON COLUMN app_core.user_decks.display_name                IS 'Локальное имя колоды, которое видит пользователь.';
COMMENT ON COLUMN app_core.user_decks.display_description         IS 'Локальное описание колоды.';
COMMENT ON COLUMN app_core.user_decks.created_at                  IS 'Дата и время создания пользовательской колоды/подписки.';
COMMENT ON COLUMN app_core.user_decks.last_synced_at              IS 'Дата и время последней синхронизации с публичной колодой.';
COMMENT ON COLUMN app_core.user_decks.is_archived                 IS 'Флаг архивации: true, если колода скрыта/в архиве.';


-- Карты в пользовательской колоде (прогресс, кастомные карты)
CREATE TABLE IF NOT EXISTS app_core.user_cards
(
    user_card_id     UUID        NOT NULL DEFAULT gen_random_uuid(),
    -- Идентификатор пользовательской карты

    user_id          UUID        NOT NULL,
    -- Владелец карты (дублируется для быстрых выборок по пользователю)

    subscription_id  UUID        NOT NULL,
    -- Ссылка на user_decks.user_deck_id (в какой пользовательской колоде эта карта находится)

    public_card_id   UUID,
    -- Ссылка на public_cards.card_id (NULL, если карта полностью кастомная)

    is_custom        BOOLEAN     NOT NULL DEFAULT true,
    -- true, если карта создана пользователем; false, если пришла из публичной колоды

    is_deleted       BOOLEAN     NOT NULL DEFAULT false,
    -- soft delete: скрыто ли карту у пользователя (для синка с публичной колодой)

    personal_note    TEXT,
    -- Персональная заметка пользователя по карте

    content_override JSONB,
    -- Локальное переопределение контента карты (если юзер изменил поля)

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Дата и время создания пользовательской карты

    updated_at       TIMESTAMPTZ,
    -- Дата и время последнего обновления пользовательской карты

    last_review_at   TIMESTAMPTZ,
    -- Дата и время последнего ревью по этой карте (для ускорения выборок)

    next_review_at   TIMESTAMPTZ,
    -- Дата и время следующего ревью (кэш из sr_card_states)

    review_count     INT         NOT NULL DEFAULT 0,
    -- Общее количество ревью по этой карте

    is_suspended     BOOLEAN     NOT NULL DEFAULT false,
    -- Временно выключена из расписания ревью

    PRIMARY KEY (user_card_id)
);

COMMENT ON TABLE  app_core.user_cards                           IS 'Карты в пользовательских колодах с прогрессом и локальными модификациями.';
COMMENT ON COLUMN app_core.user_cards.user_card_id              IS 'Уникальный идентификатор пользовательской карты.';
COMMENT ON COLUMN app_core.user_cards.user_id                   IS 'Владелец карты.';
COMMENT ON COLUMN app_core.user_cards.subscription_id           IS 'Ссылка на пользовательскую колоду (user_decks.user_deck_id).';
COMMENT ON COLUMN app_core.user_cards.public_card_id            IS 'Ссылка на карту в публичной колоде (public_cards.card_id), NULL для локальных карт.';
COMMENT ON COLUMN app_core.user_cards.is_custom                 IS 'Флаг: true, если карта создана пользователем, а не пришла из публичной колоды.';
COMMENT ON COLUMN app_core.user_cards.is_deleted                IS 'Флаг soft delete: карта скрыта у пользователя, но используется для синхронизации.';
COMMENT ON COLUMN app_core.user_cards.personal_note             IS 'Персональная заметка к карте.';
COMMENT ON COLUMN app_core.user_cards.content_override          IS 'Локальное переопределение контента карты (JSONB).';
COMMENT ON COLUMN app_core.user_cards.created_at                IS 'Дата и время создания пользовательской карты.';
COMMENT ON COLUMN app_core.user_cards.updated_at                IS 'Дата и время последнего обновления пользовательской карты.';
COMMENT ON COLUMN app_core.user_cards.last_review_at            IS 'Дата и время последнего ревью.';
COMMENT ON COLUMN app_core.user_cards.next_review_at            IS 'Дата и время следующего ревью (кэш).';
COMMENT ON COLUMN app_core.user_cards.review_count              IS 'Общее количество ревью по этой карте.';
COMMENT ON COLUMN app_core.user_cards.is_suspended              IS 'Флаг: карта временно исключена из расписания ревью.';


-- Шаблоны карт (каркас полей/лейаута/AI-профиля)
CREATE TABLE IF NOT EXISTS app_core.card_templates
(
    template_id UUID        NOT NULL DEFAULT gen_random_uuid(),
    -- Идентификатор шаблона карты

    owner_id    UUID        NOT NULL,
    -- Владелец/создатель шаблона (user_id или системный user)

    name        TEXT        NOT NULL,
    -- Название шаблона (Basic, Cloze, Japanese Word и т.д.)

    description TEXT,
    -- Описание, как использовать шаблон

    is_public   BOOLEAN     NOT NULL DEFAULT false,
    -- Можно ли использовать шаблон другим пользователям

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Дата и время создания шаблона

    updated_at  TIMESTAMPTZ,
    -- Дата и время последнего обновления шаблона

    layout      JSONB,
    -- Описание лейаута фронта/бека (блоки, порядок, условные отображения)

    ai_profile  JSONB,
    -- Профиль для AI-генерации карт на основе шаблона (промпты, маппинг полей и т.п.)

    icon_url    TEXT,
    -- Иконка/обложка шаблона

    PRIMARY KEY (template_id)
);

COMMENT ON TABLE  app_core.card_templates                     IS 'Шаблоны карт: набор полей, лейаут и AI-профиль.';
COMMENT ON COLUMN app_core.card_templates.template_id         IS 'Идентификатор шаблона карты.';
COMMENT ON COLUMN app_core.card_templates.owner_id            IS 'Создатель шаблона (user_id или системный).';
COMMENT ON COLUMN app_core.card_templates.name                IS 'Название шаблона.';
COMMENT ON COLUMN app_core.card_templates.description         IS 'Описание шаблона и сценариев использования.';
COMMENT ON COLUMN app_core.card_templates.is_public           IS 'Флаг: доступен ли шаблон другим пользователям.';
COMMENT ON COLUMN app_core.card_templates.created_at          IS 'Дата и время создания шаблона.';
COMMENT ON COLUMN app_core.card_templates.updated_at          IS 'Дата и время последнего обновления шаблона.';
COMMENT ON COLUMN app_core.card_templates.layout              IS 'JSONB-описание лейаута (front/back, блоки, условия).';
COMMENT ON COLUMN app_core.card_templates.ai_profile          IS 'JSONB-профиль для AI-генерации карт по шаблону.';
COMMENT ON COLUMN app_core.card_templates.icon_url            IS 'URL иконки шаблона.';


-- Поля шаблона карты (структура контента)
CREATE TABLE IF NOT EXISTS app_core.field_templates
(
    field_id      UUID            NOT NULL DEFAULT gen_random_uuid(),
    -- Идентификатор поля в шаблоне

    template_id   UUID            NOT NULL,
    -- Ссылка на шаблон карты (card_templates.template_id)

    name          TEXT            NOT NULL,
    -- Машинное имя поля (ключ в JSON content.fields, например "front", "back")

    label         TEXT            NOT NULL,
    -- Человеческое название поля в UI ("Лицевая сторона")

    field_type    card_field_type NOT NULL,
    -- Тип поля (text, rich_text, image, audio, ...)

    is_required   BOOLEAN         NOT NULL DEFAULT false,
    -- Обязательность заполнения поля

    is_on_front   BOOLEAN         NOT NULL DEFAULT false,
    -- Участвует ли поле в фронте карты (true/false)

    order_index   INT             NOT NULL DEFAULT 0,
    -- Порядок поля в форме редактирования

    default_value TEXT,
    -- Дефолтное значение поля

    help_text     TEXT,
    -- Подсказка по заполнению поля

    PRIMARY KEY (field_id)
);

COMMENT ON TABLE  app_core.field_templates                     IS 'Описания полей для шаблонов карт.';
COMMENT ON COLUMN app_core.field_templates.field_id            IS 'Идентификатор поля шаблона.';
COMMENT ON COLUMN app_core.field_templates.template_id         IS 'Ссылка на шаблон (card_templates.template_id).';
COMMENT ON COLUMN app_core.field_templates.name                IS 'Машинное имя поля (ключ в JSON контента).';
COMMENT ON COLUMN app_core.field_templates.label               IS 'Человеческий лейбл поля в UI.';
COMMENT ON COLUMN app_core.field_templates.field_type          IS 'Тип поля (ENUM card_field_type).';
COMMENT ON COLUMN app_core.field_templates.is_required         IS 'Флаг обязательности поля.';
COMMENT ON COLUMN app_core.field_templates.is_on_front         IS 'Флаг: участвует ли поле в фронте карты.';
COMMENT ON COLUMN app_core.field_templates.order_index         IS 'Порядок отображения поля в форме.';
COMMENT ON COLUMN app_core.field_templates.default_value       IS 'Дефолтное значение поля.';
COMMENT ON COLUMN app_core.field_templates.help_text           IS 'Подсказка по заполнению поля.';


-- Алгоритмы SRS
CREATE TABLE IF NOT EXISTS app_core.sr_algorithms
(
    algorithm_id   TEXT        NOT NULL,
    -- Машинный идентификатор алгоритма (fsrs_v4, sm2, ...)

    name           TEXT        NOT NULL,
    -- Человекочитаемое имя алгоритма

    description    TEXT,
    -- Описание алгоритма и его особенностей

    version        TEXT,
    -- Версия реализации алгоритма (например, "4.0" для FSRS v4)

    config_schema  JSONB,
    -- JSON-схема конфигурации алгоритма (для UI)

    default_config JSONB,
    -- Конфиг по умолчанию для алгоритма

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Дата и время регистрации алгоритма в системе

    PRIMARY KEY (algorithm_id)
);

COMMENT ON TABLE  app_core.sr_algorithms                       IS 'Реализованные в системе алгоритмы spaced repetition.';
COMMENT ON COLUMN app_core.sr_algorithms.algorithm_id          IS 'Машинный идентификатор алгоритма (используется в ссылках).';
COMMENT ON COLUMN app_core.sr_algorithms.name                  IS 'Отображаемое название алгоритма.';
COMMENT ON COLUMN app_core.sr_algorithms.description           IS 'Описание алгоритма.';
COMMENT ON COLUMN app_core.sr_algorithms.version               IS 'Версия реализации алгоритма.';
COMMENT ON COLUMN app_core.sr_algorithms.config_schema         IS 'JSON-схема возможных настроек алгоритма.';
COMMENT ON COLUMN app_core.sr_algorithms.default_config        IS 'Конфигурация алгоритма по умолчанию.';
COMMENT ON COLUMN app_core.sr_algorithms.created_at            IS 'Дата и время создания записи об алгоритме.';


-- Текущее состояние алгоритма по каждой пользовательской карте
CREATE TABLE IF NOT EXISTS app_core.sr_card_states
(
    user_card_id   UUID        NOT NULL,
    -- Ссылка на пользовательскую карту (user_cards.user_card_id)

    algorithm_id   TEXT        NOT NULL,
    -- Алгоритм, по которому сейчас считается эта карта

    state          JSONB       NOT NULL,
    -- Произвольное состояние алгоритма (stability, difficulty, ...)

    last_review_at TIMESTAMPTZ,
    -- Дата и время последнего ревью (для алгоритма)

    next_review_at TIMESTAMPTZ,
    -- Дата и время следующего ревью (вычислено алгоритмом)

    review_count   INT         NOT NULL DEFAULT 0,
    -- Количество ревью, прошедших через этот алгоритм

    PRIMARY KEY (user_card_id)
);

COMMENT ON TABLE  app_core.sr_card_states                      IS 'Текущее состояние алгоритма SRS для каждой пользовательской карты.';
COMMENT ON COLUMN app_core.sr_card_states.user_card_id         IS 'Ссылка на пользовательскую карту (user_cards.user_card_id).';
COMMENT ON COLUMN app_core.sr_card_states.algorithm_id         IS 'Алгоритм, по которому ведётся состояние.';
COMMENT ON COLUMN app_core.sr_card_states.state                IS 'JSONB-состояние карты для алгоритма (например, поля FSRS).';
COMMENT ON COLUMN app_core.sr_card_states.last_review_at       IS 'Дата и время последнего ревью для алгоритма.';
COMMENT ON COLUMN app_core.sr_card_states.next_review_at       IS 'Дата и время следующего ревью (из алгоритма).';
COMMENT ON COLUMN app_core.sr_card_states.review_count         IS 'Общее количество ревью, учтённых в состоянии.';


-- Лог всех ревью (для реконструкции состояния и анализа)
CREATE TABLE IF NOT EXISTS app_core.sr_review_logs
(
    id           BIGSERIAL     PRIMARY KEY,
    -- Уникальный идентификатор записи логов

    user_card_id UUID          NOT NULL,
    -- Пользовательская карта, по которой было ревью

    algorithm_id TEXT          NOT NULL,
    -- Алгоритм, который использовался при этом ревью

    reviewed_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Время ревью

    rating       SMALLINT,
    -- Оценка ответа пользователя (интерпретация зависит от алгоритма, напр. 0..3)

    response_ms  INT,
    -- Время ответа в миллисекундах

    state_before JSONB,
    -- Состояние алгоритма до применения ревью (snapshot)

    state_after  JSONB,
    -- Состояние алгоритма после применения ревью (snapshot)

    source       review_source,
    -- Источник ревью (web, mobile, api и т.п.)

    CHECK (rating IS NULL OR rating >= 0)
);

COMMENT ON TABLE  app_core.sr_review_logs                      IS 'Лог всех ревью по пользовательским картам.';
COMMENT ON COLUMN app_core.sr_review_logs.id                   IS 'Уникальный идентификатор записи ревью.';
COMMENT ON COLUMN app_core.sr_review_logs.user_card_id         IS 'Пользовательская карта, по которой было ревью.';
COMMENT ON COLUMN app_core.sr_review_logs.algorithm_id         IS 'Алгоритм, применённый при этом ревью.';
COMMENT ON COLUMN app_core.sr_review_logs.reviewed_at          IS 'Дата и время ревью.';
COMMENT ON COLUMN app_core.sr_review_logs.rating               IS 'Оценка ответа пользователя (интерпретация зависит от алгоритма).';
COMMENT ON COLUMN app_core.sr_review_logs.response_ms          IS 'Время ответа пользователя в миллисекундах.';
COMMENT ON COLUMN app_core.sr_review_logs.state_before         IS 'Состояние алгоритма до применения ревью.';
COMMENT ON COLUMN app_core.sr_review_logs.state_after          IS 'Состояние алгоритма после применения ревью.';
COMMENT ON COLUMN app_core.sr_review_logs.source               IS 'Источник ревью (web/mobile/api/import/other).';


-- =========================
-- Внешние ключи и индексы
-- =========================

-- public_decks.template_id -> card_templates
ALTER TABLE app_core.public_decks
    ADD CONSTRAINT fk_public_decks_template
        FOREIGN KEY (template_id) REFERENCES app_core.card_templates (template_id);

-- public_cards.deck_id + deck_version -> public_decks(deck_id, version)
ALTER TABLE app_core.public_cards
    ADD CONSTRAINT fk_public_cards_deck
        FOREIGN KEY (deck_id, deck_version)
            REFERENCES app_core.public_decks (deck_id, version)
            ON DELETE CASCADE;

-- user_decks.public_deck_id + subscribed_version/current_version -> public_decks
ALTER TABLE app_core.user_decks
    ADD CONSTRAINT fk_user_decks_public_deck_subscribed
        FOREIGN KEY (public_deck_id, subscribed_version)
            REFERENCES app_core.public_decks (deck_id, version)
            DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE app_core.user_decks
    ADD CONSTRAINT fk_user_decks_public_deck_current
        FOREIGN KEY (public_deck_id, current_version)
            REFERENCES app_core.public_decks (deck_id, version)
            DEFERRABLE INITIALLY DEFERRED;

-- user_cards.subscription_id -> user_decks
ALTER TABLE app_core.user_cards
    ADD CONSTRAINT fk_user_cards_subscription
        FOREIGN KEY (subscription_id)
            REFERENCES app_core.user_decks (user_deck_id)
            ON DELETE CASCADE;

-- user_cards.public_card_id -> public_cards (через частичный ключ card_id)
-- здесь предполагается, что card_id уникален глобально
ALTER TABLE app_core.public_cards
    ADD CONSTRAINT uq_public_cards_card_id UNIQUE (card_id);

ALTER TABLE app_core.user_cards
    ADD CONSTRAINT fk_user_cards_public_card
        FOREIGN KEY (public_card_id)
            REFERENCES app_core.public_cards (card_id);

-- field_templates.template_id -> card_templates
ALTER TABLE app_core.field_templates
    ADD CONSTRAINT fk_field_templates_template
        FOREIGN KEY (template_id)
            REFERENCES app_core.card_templates (template_id)
            ON DELETE CASCADE;

-- user_decks.algorithm_id -> sr_algorithms
ALTER TABLE app_core.user_decks
    ADD CONSTRAINT fk_user_decks_algorithm
        FOREIGN KEY (algorithm_id)
            REFERENCES app_core.sr_algorithms (algorithm_id);

-- sr_card_states.user_card_id -> user_cards
ALTER TABLE app_core.sr_card_states
    ADD CONSTRAINT fk_sr_card_states_user_card
        FOREIGN KEY (user_card_id)
            REFERENCES app_core.user_cards (user_card_id)
            ON DELETE CASCADE;

-- sr_card_states.algorithm_id -> sr_algorithms
ALTER TABLE app_core.sr_card_states
    ADD CONSTRAINT fk_sr_card_states_algorithm
        FOREIGN KEY (algorithm_id)
            REFERENCES app_core.sr_algorithms (algorithm_id);

-- sr_review_logs.user_card_id -> user_cards
ALTER TABLE app_core.sr_review_logs
    ADD CONSTRAINT fk_sr_review_logs_user_card
        FOREIGN KEY (user_card_id)
            REFERENCES app_core.user_cards (user_card_id)
            ON DELETE CASCADE;

-- sr_review_logs.algorithm_id -> sr_algorithms
ALTER TABLE app_core.sr_review_logs
    ADD CONSTRAINT fk_sr_review_logs_algorithm
        FOREIGN KEY (algorithm_id)
            REFERENCES app_core.sr_algorithms (algorithm_id);

-- Индекс, чтобы нельзя было подписаться на одну и ту же публичную колоду несколько раз
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_decks_user_public_deck
    ON app_core.user_decks (user_id, public_deck_id)
    WHERE public_deck_id IS NOT NULL;

-- Пара полезных индексов под выборки
CREATE INDEX IF NOT EXISTS ix_user_cards_user
    ON app_core.user_cards (user_id);

CREATE INDEX IF NOT EXISTS ix_user_cards_next_review
    ON app_core.user_cards (user_id, next_review_at)
    WHERE is_deleted = false AND is_suspended = false;

CREATE INDEX IF NOT EXISTS ix_sr_review_logs_user_card
    ON app_core.sr_review_logs (user_card_id, reviewed_at);

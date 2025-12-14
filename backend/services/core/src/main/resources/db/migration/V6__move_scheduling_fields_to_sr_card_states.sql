-- 1) Добавляем suspended в sr_card_states (если ещё нет)
ALTER TABLE app_core.sr_card_states
    ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN NOT NULL DEFAULT false;

-- 2) Бэкап/перенос текущих значений из user_cards в sr_card_states (если вдруг что-то уже было записано)
UPDATE app_core.sr_card_states s
SET
    last_review_at = COALESCE(s.last_review_at, c.last_review_at),
    next_review_at = COALESCE(s.next_review_at, c.next_review_at),
    review_count   = GREATEST(s.review_count, c.review_count),
    is_suspended   = (s.is_suspended OR c.is_suspended)
FROM app_core.user_cards c
WHERE c.user_card_id = s.user_card_id;

-- 3) Удаляем индекс, который больше не имеет смысла (он по user_cards.next_review_at)
DROP INDEX IF EXISTS app_core.ix_user_cards_next_review;

-- 4) Удаляем колонки планирования из user_cards
ALTER TABLE app_core.user_cards DROP COLUMN IF EXISTS last_review_at;
ALTER TABLE app_core.user_cards DROP COLUMN IF EXISTS next_review_at;
ALTER TABLE app_core.user_cards DROP COLUMN IF EXISTS review_count;
ALTER TABLE app_core.user_cards DROP COLUMN IF EXISTS is_suspended;

-- 5) Индексы под новые типовые выборки
CREATE INDEX IF NOT EXISTS ix_sr_card_states_next_review
    ON app_core.sr_card_states (next_review_at)
    WHERE is_suspended = false;

CREATE INDEX IF NOT EXISTS ix_user_cards_deck_active
    ON app_core.user_cards (subscription_id, user_id)
    WHERE is_deleted = false;

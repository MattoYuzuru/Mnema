-- Paywall and goal onboarding (#301, AI-19).
--
-- usage_allowance.source learns PROMO: the entitlement source reads promo snapshots of entitlement_inbox too, and the allowance row
-- remembers which source it was granted from.
ALTER TABLE app_learning.usage_allowance DROP CONSTRAINT IF EXISTS usage_allowance_source_check;
ALTER TABLE app_learning.usage_allowance
    ADD CONSTRAINT usage_allowance_source_check CHECK (source IN ('CONFIG', 'BILLING', 'PROMO'));

-- learning_profile: the owner's one answer to «Для чего вам Mnema?». goal NULL with answered_at set is a skip; no row is not yet
-- answered. The goal only changes copy and the recommended tier; it never reaches an AI provider.
CREATE TABLE app_learning.learning_profile (
    owner_id UUID PRIMARY KEY,
    goal TEXT CHECK (goal IS NULL OR goal IN ('EXAMS', 'INTERVIEW', 'LANGUAGE', 'WORK', 'SELF')),
    answered_at TIMESTAMPTZ NOT NULL CHECK (isfinite(answered_at))
);

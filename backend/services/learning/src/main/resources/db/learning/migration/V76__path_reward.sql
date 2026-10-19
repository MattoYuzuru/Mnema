-- Path reward (OB-402, #539; docs/engineering/onboarding/adr/adr-onb-005-path-reward.md, contracts/onboarding/README.md «Path reward», contracts/usage/promo.json).
--
-- promo_campaign: an owner-made campaign that issues personal promo codes on an event. The only type is PATH_REWARD (100 % of the visible milestones of the
-- «Путь Mnema»); its offer is a discount percent (optionally for one plan) or a tier for N days, with the shapes of promo_code. At most one campaign of a type is
-- enabled (the kill switch of the console; learning.onboarding.reward.enabled is the global one). A campaign is immutable except for enabled: a new offer is a new
-- campaign, so every issued code keeps the terms it was issued under.
SET LOCAL lock_timeout = '5s';
CREATE TABLE app_learning.promo_campaign (
    campaign_id UUID PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('PATH_REWARD')),
    offer_type TEXT NOT NULL CHECK (offer_type IN ('DISCOUNT_PERCENT', 'TIER_DAYS')),
    plan TEXT CHECK (plan IN ('PLUS', 'PRO')),
    days INTEGER CHECK (days BETWEEN 1 AND 366),
    percent INTEGER CHECK (percent BETWEEN 1 AND 90),
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL CHECK (isfinite(created_at)),
    created_by UUID NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL CHECK (isfinite(updated_at)),
    CHECK ((offer_type = 'TIER_DAYS' AND plan IS NOT NULL AND days IS NOT NULL AND percent IS NULL)
        OR (offer_type = 'DISCOUNT_PERCENT' AND percent IS NOT NULL AND days IS NULL))
);
CREATE UNIQUE INDEX promo_campaign_one_enabled ON app_learning.promo_campaign(type) WHERE enabled;

-- A personal code: issued by a campaign to one account (recipient_id), redeemable by that account only (another account gets PROMO_INVALID, as for an unknown
-- code). The owner's code list leaves campaign codes out; the campaign lists its issuances. The plain code is never stored: it is derived from the code id under
-- the promo secret when the recipient's view needs it.
ALTER TABLE app_learning.promo_code ADD COLUMN campaign_id UUID REFERENCES app_learning.promo_campaign(campaign_id);
ALTER TABLE app_learning.promo_code ADD COLUMN recipient_id UUID;
ALTER TABLE app_learning.promo_code ADD CONSTRAINT promo_code_personal CHECK ((campaign_id IS NULL) = (recipient_id IS NULL));
-- The owner's list pages the codes that are not personal, newest first (created_at DESC, code_id DESC); personal codes may be many, so the list must neither walk over
-- them nor sort: the index answers the order and stops at the page.
CREATE INDEX promo_code_admin_list ON app_learning.promo_code(created_at DESC, code_id DESC) WHERE campaign_id IS NULL;

-- onboarding_reward: the one reward of an account (account_id is the key: a replayed tour or a second campaign never issues another; the issuer writes it under an
-- account-scoped advisory lock after the code, so the key is only the backstop), the audit of the issuance
-- (which campaign, which code, when, until when) that the console lists. expires_at is issued_at + 7 days and equals the code's valid_until.
-- TODO(account-deletion task; owner: Learning's account-purge epic #409): delete the row by account_id with onboarding_journey; the promo code row stays with the
-- promo ledger (its recipient_id is cleared or the code deleted with the redemption audit, as the promo retention decides — docs/product/russia-legal-launch-checklist-2026.md).
CREATE TABLE app_learning.onboarding_reward (
    account_id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL REFERENCES app_learning.promo_campaign(campaign_id),
    promo_code_id UUID NOT NULL UNIQUE REFERENCES app_learning.promo_code(code_id),
    issued_at TIMESTAMPTZ NOT NULL CHECK (isfinite(issued_at)),
    expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(expires_at) AND expires_at > issued_at)
);
-- The console's issuance list and the daily limit of a campaign (issuances of the last 24 hours).
CREATE INDEX onboarding_reward_campaign ON app_learning.onboarding_reward(campaign_id, issued_at);

ALTER TABLE app_learning.admin_audit DROP CONSTRAINT admin_audit_action_check;
ALTER TABLE app_learning.admin_audit ADD CONSTRAINT admin_audit_action_check CHECK (action IN
    ('EVENT_CREATE','EVENT_REPLACE','EVENT_DELETE','PROMO_CREATE','PROMO_ENABLE','PROMO_DISABLE','COMPLAINT_DECIDE','COMPLAINT_UNHIDE','COMMUNITY_ANOMALY_DISMISS',
     'COMMUNITY_EDITORIAL_UPDATE','PROMO_CAMPAIGN_CREATE','PROMO_CAMPAIGN_ENABLE','PROMO_CAMPAIGN_DISABLE'));

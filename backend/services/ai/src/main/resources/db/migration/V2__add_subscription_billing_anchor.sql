ALTER TABLE IF EXISTS app_ai.subscriptions
    ADD COLUMN IF NOT EXISTS billing_anchor INT NOT NULL DEFAULT 1;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_subscriptions_billing_anchor') THEN
            ALTER TABLE app_ai.subscriptions
                ADD CONSTRAINT chk_subscriptions_billing_anchor
                    CHECK (billing_anchor BETWEEN 1 AND 31);
        END IF;
    END
$$ LANGUAGE plpgsql;

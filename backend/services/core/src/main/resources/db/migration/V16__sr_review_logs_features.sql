alter table app_core.sr_review_logs
    add column if not exists features jsonb;

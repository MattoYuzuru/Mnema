alter table app_core.user_decks
    add column if not exists row_version bigint not null default 0;

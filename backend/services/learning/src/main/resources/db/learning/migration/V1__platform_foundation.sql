CREATE TABLE app_learning.command_receipt (
    command_id UUID PRIMARY KEY,
    actor_id UUID NOT NULL,
    command_scope VARCHAR(80) NOT NULL,
    command_type VARCHAR(80) NOT NULL,
    payload_hash BYTEA NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ck_command_receipt_scope
        CHECK (command_scope ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'),
    CONSTRAINT ck_command_receipt_type
        CHECK (command_type ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'),
    CONSTRAINT ck_command_receipt_payload_hash
        CHECK (octet_length(payload_hash) = 32)
);

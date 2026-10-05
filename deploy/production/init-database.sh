#!/bin/sh
set -eu
# getenv keeps passwords out of argv and generated SQL files/logs.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
\getenv identity_password MNEMA_IDENTITY_DB_PASSWORD
\getenv learning_password MNEMA_LEARNING_DB_PASSWORD
CREATE ROLE app_identity LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'identity_password';
CREATE ROLE app_learning LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'learning_password';
REVOKE ALL ON DATABASE mnema FROM PUBLIC;
GRANT CONNECT ON DATABASE mnema TO app_identity, app_learning;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA app_identity AUTHORIZATION app_identity;
CREATE SCHEMA app_learning AUTHORIZATION app_learning;
REVOKE ALL ON SCHEMA app_identity, app_learning FROM PUBLIC;
CREATE EXTENSION pg_trgm WITH SCHEMA app_learning;
SQL

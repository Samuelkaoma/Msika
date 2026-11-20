#!/bin/bash
# Runs once, the first time the Postgres container starts with an empty data folder.
# Creates the three databases and the roles that use them. Passwords come from environment
# variables (see infra/.env.example); the defaults in docker-compose.yml are for local use only.
#
# Msika uses two roles on purpose (AGENTS.md rule 4):
#   msika_owner  owns the tables and runs the Flyway migrations
#   msika_app    what the running app connects as: owns nothing, cannot bypass row-level security
set -euo pipefail

: "${MSIKA_OWNER_PASSWORD:?set MSIKA_OWNER_PASSWORD}"
: "${MSIKA_APP_PASSWORD:?set MSIKA_APP_PASSWORD}"
: "${ZAMSIM_PASSWORD:?set ZAMSIM_PASSWORD}"
: "${ELEVYSIM_PASSWORD:?set ELEVYSIM_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<SQL
CREATE ROLE msika_owner LOGIN PASSWORD '${MSIKA_OWNER_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE msika_app   LOGIN PASSWORD '${MSIKA_APP_PASSWORD}'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE zamsim      LOGIN PASSWORD '${ZAMSIM_PASSWORD}'      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE elevysim    LOGIN PASSWORD '${ELEVYSIM_PASSWORD}'    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

CREATE DATABASE msika    OWNER msika_owner;
CREATE DATABASE zamsim   OWNER zamsim;
CREATE DATABASE elevysim OWNER elevysim;

REVOKE ALL ON DATABASE msika    FROM PUBLIC;
REVOKE ALL ON DATABASE zamsim   FROM PUBLIC;
REVOKE ALL ON DATABASE elevysim FROM PUBLIC;
GRANT CONNECT ON DATABASE msika TO msika_app;
SQL

# What msika_app may do in the msika database: use the public schema and read and write tables and
# sequences that msika_owner creates later. It can never create, alter or drop anything.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname msika <<SQL
GRANT USAGE ON SCHEMA public TO msika_app;
ALTER DEFAULT PRIVILEGES FOR ROLE msika_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO msika_app;
ALTER DEFAULT PRIVILEGES FOR ROLE msika_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO msika_app;
SQL

#!/usr/bin/env bash
#
# Creates the PostgreSQL role, database and schema this service needs.
#
# Run it against a PostgreSQL you can already reach as a superuser — a local install, a Docker
# container, whatever. It is idempotent: re-running drops and recreates the tables, so it is also
# how you get back to a clean slate.
#
# Usage:
#   ./db/setup.sh                    # defaults below
#   PGDATABASE=hotelbooking_test ./db/setup.sh
#
set -euo pipefail

DB_NAME="${PGDATABASE:-hotelbooking}"
DB_USER="${DB_USER:-hotelbooking}"
DB_PASSWORD="${DB_PASSWORD:-hotelbooking}"
DB_HOST="${PGHOST:-localhost}"
DB_PORT="${PGPORT:-5432}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Homebrew's postgresql@16 is keg-only, so psql is usually not on PATH even when the server is up.
if ! command -v psql >/dev/null 2>&1; then
  for candidate in /opt/homebrew/opt/postgresql@1[0-9]/bin /usr/local/opt/postgresql@1[0-9]/bin; do
    [ -x "$candidate/psql" ] && PATH="$candidate:$PATH" && break
  done
fi
command -v psql >/dev/null 2>&1 || {
  echo "psql not found. Install PostgreSQL (brew install postgresql@16) or add psql to PATH." >&2
  exit 1
}

pg_isready -h "$DB_HOST" -p "$DB_PORT" >/dev/null 2>&1 || {
  echo "No PostgreSQL server at $DB_HOST:$DB_PORT. Start it with: brew services start postgresql@16" >&2
  exit 1
}

echo "==> Role '$DB_USER'"
psql -h "$DB_HOST" -p "$DB_PORT" -d postgres -v ON_ERROR_STOP=1 -q <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$DB_USER') THEN
    CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASSWORD';
  END IF;
END \$\$;
SQL

echo "==> Database '$DB_NAME'"
if ! psql -h "$DB_HOST" -p "$DB_PORT" -d postgres -tAc \
     "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
  createdb -h "$DB_HOST" -p "$DB_PORT" -O "$DB_USER" "$DB_NAME"
fi

# PostgreSQL 15+ stopped granting CREATE on `public` to non-owners, so Hibernate (and this script's
# own DDL) cannot create tables without this. Easy to miss: it fails at first write, not at connect.
psql -h "$DB_HOST" -p "$DB_PORT" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q \
  -c "ALTER SCHEMA public OWNER TO $DB_USER;" \
  -c "GRANT ALL ON SCHEMA public TO $DB_USER;"

echo "==> Schema"
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 -q -f "$SCRIPT_DIR/schema.sql"

tables=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -tAc "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")

echo
echo "Ready: $tables tables in '$DB_NAME' on $DB_HOST:$DB_PORT"
echo
echo "Start the service against it with:"
echo "    SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run"
echo
echo "To have Hibernate verify this schema matches the entity model rather than modify it:"
echo "    SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run \\"
echo "      -Dspring-boot.run.jvmArguments=-Dspring.jpa.hibernate.ddl-auto=validate"

#!/usr/bin/env bash
set -euo pipefail

# Creates or updates the three public demo projects through the Admin API, runs
# project migrations, and then loads deterministic sample facts through psql.

: "${ADMIN_TOKEN:?Set ADMIN_TOKEN to the local AnalyticsHub admin token}"
: "${DB_PASSWORD:?Set DB_PASSWORD for the PostgreSQL role used by demo projects}"

API_BASE_URL="${API_BASE_URL:-http://127.0.0.1:3001/api}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-analytics}"
DB_USER="${DB_USER:-analytic}"
PROJECT_DB_HOST="${PROJECT_DB_HOST:-$DB_HOST}"

for command in curl jq; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Missing required command: $command" >&2
    exit 1
  }
done

if [[ ! "$DB_USER" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
  echo "DB_USER must be a PostgreSQL identifier" >&2
  exit 1
fi

if command -v psql >/dev/null 2>&1; then
  run_psql() {
    psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"
  }
  run_seed_file() {
    run_psql -f "$1"
  }
elif [[ -n "${POSTGRES_CONTAINER:-}" ]] && command -v docker >/dev/null 2>&1; then
  run_psql() {
    docker exec -e PGPASSWORD="$DB_PASSWORD" "$POSTGRES_CONTAINER" \
      psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"
  }
  run_seed_file() {
    docker exec -i -e PGPASSWORD="$DB_PASSWORD" "$POSTGRES_CONTAINER" \
      psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" <"$1"
  }
else
  echo "Install psql, or set POSTGRES_CONTAINER to a running PostgreSQL container" >&2
  exit 1
fi

api() {
  curl --fail --silent --show-error \
    -H "X-Admin-Token: ${ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    "$@"
}

projects_json="$(api "${API_BASE_URL}/admin/projects")"

upsert_project() {
  local project_id="$1"
  local project_name="$2"
  local template="$3"
  local schema="$4"
  local existing_id payload response numeric_id

  existing_id="$(jq -r --arg project_id "$project_id" \
    '.data[] | select(.projectId == $project_id) | .id' <<<"$projects_json" | head -n 1)"

  if [[ -n "$existing_id" ]]; then
    payload="$(jq -n \
      --arg projectName "$project_name" \
      --arg analysisTemplate "$template" \
      --arg dbHost "$PROJECT_DB_HOST" \
      --argjson dbPort "$DB_PORT" \
      --arg dbName "$DB_NAME" \
      --arg dbSchema "$schema" \
      --arg dbUser "$DB_USER" \
      --arg dbPassword "$DB_PASSWORD" \
      '{projectName:$projectName,analysisTemplate:$analysisTemplate,dbHost:$dbHost,dbPort:$dbPort,dbName:$dbName,dbSchema:$dbSchema,dbUser:$dbUser,dbPassword:$dbPassword,tablePrefix:"analytics_",isActive:true}')"
    response="$(api -X PUT -d "$payload" "${API_BASE_URL}/admin/projects/${existing_id}")"
  else
    payload="$(jq -n \
      --arg projectId "$project_id" \
      --arg projectName "$project_name" \
      --arg analysisTemplate "$template" \
      --arg dbHost "$PROJECT_DB_HOST" \
      --argjson dbPort "$DB_PORT" \
      --arg dbName "$DB_NAME" \
      --arg dbSchema "$schema" \
      --arg dbUser "$DB_USER" \
      --arg dbPassword "$DB_PASSWORD" \
      '{projectId:$projectId,projectName:$projectName,analysisTemplate:$analysisTemplate,dbHost:$dbHost,dbPort:$dbPort,dbName:$dbName,dbSchema:$dbSchema,dbUser:$dbUser,dbPassword:$dbPassword,tablePrefix:"analytics_"}')"
    response="$(api -X POST -d "$payload" "${API_BASE_URL}/admin/projects")"
  fi

  numeric_id="$(jq -er '.data.id' <<<"$response")"
  api -X POST "${API_BASE_URL}/admin/projects/${numeric_id}/init" >/dev/null
  echo "Prepared ${project_id} (${template})"
}

export PGPASSWORD="$DB_PASSWORD"
for schema in demo_app demo_website demo_webapp; do
  run_psql -c "CREATE SCHEMA IF NOT EXISTS ${schema} AUTHORIZATION ${DB_USER}" >/dev/null
done

upsert_project demo_app "Demo Mobile App" app demo_app
upsert_project demo_website "Demo Marketing Website" website demo_website
upsert_project demo_webapp "Demo SaaS WebApp" webapp demo_webapp

run_seed_file "$(cd "$(dirname "$0")" && pwd)/seed.sql"

echo "Demo data is ready: demo_app, demo_website, demo_webapp"

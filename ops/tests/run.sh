#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

bash "$TEST_DIR/create-analytics-database-test.sh"
bash "$TEST_DIR/check-app-config-test.sh"
bash "$TEST_DIR/setup-postgresql-repo-test.sh"

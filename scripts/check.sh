#!/usr/bin/env bash
# The local quality gate. The same checks run in CI (see CONTRIBUTING.md).
#   scripts/check.sh --fast   unit tests, lint, typecheck (about a minute)
#   scripts/check.sh          everything: also integration tests (need Docker) and full builds
set -euo pipefail
cd "$(dirname "$0")/.."

fast=0
case "${1:-}" in
  --fast) fast=1 ;;
  "") ;;
  *) echo "usage: scripts/check.sh [--fast]" >&2; exit 2 ;;
esac

step() { printf '\n==> %s\n' "$1"; }

step "Java: compile with lint warnings as errors, and unit tests"
./gradlew test

step "Web: install and typecheck"
pnpm install --frozen-lockfile
pnpm -r --if-present typecheck

step "Web: unit tests"
pnpm -r --if-present test

if [ "$fast" -eq 0 ]; then
  step "Java: integration tests and full build"
  ./gradlew build

  step "Web: full build"
  pnpm -r build
fi

printf '\nAll checks passed%s.\n' "$([ "$fast" -eq 1 ] && echo ' (fast)' || true)"

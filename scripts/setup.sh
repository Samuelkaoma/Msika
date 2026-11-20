#!/usr/bin/env bash
# Run once after cloning. Turns on the pre-push hook.
set -euo pipefail
cd "$(dirname "$0")/.."
git config core.hooksPath .githooks
echo "Git hooks enabled: core.hooksPath = $(git config core.hooksPath)"

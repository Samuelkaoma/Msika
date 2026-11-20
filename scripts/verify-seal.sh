#!/usr/bin/env bash
# Checks every council's daily seals against the database and says whether anything was changed.
#
#   scripts/verify-seal.sh                       check everything, using the defaults below
#   scripts/verify-seal.sh --council <uuid>      check one council
#
# It needs the OWNER role's password (row-level security hides other councils from the app role), in the
# environment so it never shows in a process list:
#   MSIKA_VERIFY_DB_PASSWORD   (or MSIKA_OWNER_PASSWORD)
# Optional: MSIKA_VERIFY_DB_URL (default jdbc:postgresql://localhost:5432/msika), MSIKA_VERIFY_DB_USER
# (default msika_owner), MSIKA_SEAL_PUBLIC_KEY (Base64) or --key-file (default .msika-keys/seal-key.properties).
# Other options: --url, --user, --public-key. See platform/msika-api/.../VerifySealsCli.java.
#
# Exit code 0: every seal verifies.  1: the ledger or a seal was altered.  2: it could not run.
# (Java is run directly, not through Gradle, so these exit codes reach you unchanged.)
set -uo pipefail
cd "$(dirname "$0")/.."

# Gradle only compiles and tells us what to run: line 1 is the java program, line 2 the classpath.
command_lines=$(./gradlew -q :msika-api:printVerifySealsCommand) || {
  echo "verify-seal: could not build the tool" >&2
  exit 2
}
java_bin=$(printf '%s\n' "$command_lines" | sed -n 1p | tr -d '\r')
classpath=$(printf '%s\n' "$command_lines" | sed -n 2p | tr -d '\r')

exec "$java_bin" -cp "$classpath" zm.msika.api.seal.VerifySealsCli "$@"

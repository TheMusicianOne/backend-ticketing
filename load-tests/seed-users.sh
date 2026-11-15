#!/usr/bin/env bash
# Creates test users, logs each one in, and writes their tokens to a CSV for Gatling.
#
# Usage: ./seed-users.sh [base-url] [number-of-users] [parallel]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
COUNT="${2:-200}"
PARALLEL="${3:-10}"
PASSWORD="load-test-password"
OUTPUT="$(dirname "$0")/src/gatling/resources/tokens.csv"

mkdir -p "$(dirname "$OUTPUT")"

seed_one() {
  local email="fan-$1@loadtest.local"

  # Register; a 409 just means the user already exists from an earlier run
  curl -s -o /dev/null -X POST "$BASE_URL/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"

  local token
  token=$(curl -s -X POST "$BASE_URL/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}" \
    | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

  echo "$email,$token"
}
export -f seed_one
export BASE_URL PASSWORD

echo "email,token" > "$OUTPUT"
seq 1 "$COUNT" | xargs -P "$PARALLEL" -I{} bash -c 'seed_one {}' >> "$OUTPUT"

echo "Wrote $COUNT users to $OUTPUT"
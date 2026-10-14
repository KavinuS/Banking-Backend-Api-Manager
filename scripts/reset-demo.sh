#!/usr/bin/env bash
# P13: put the whole POC into the known-good demo state and prove it.
# Usage: reset-demo.sh [snapshot.tgz]   (default: newest snapshots/wso2-stage-b-*.tgz)
#  1. restore WSO2 from the snapshot (apps, subscriptions, policies, v1/v2 state)
#  2. archive backend logs so the demo starts with an empty audit trail
#  3. start everything, warm up the throttle publisher, run the security suite
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SNAP=${1:-$(ls -t "$ROOT"/snapshots/wso2-stage-b-*.tgz 2>/dev/null | head -1)}
[ -f "$SNAP" ] || { echo "No snapshot found (expected snapshots/wso2-stage-b-*.tgz)" >&2; exit 1; }

echo "== Restoring $(basename "$SNAP")"
"$ROOT/scripts/restore.sh" "$SNAP"

echo "== Archiving backend logs"
LOGS="$ROOT/banking-backend/logs"
if [ -d "$LOGS" ] && ls "$LOGS"/*.log >/dev/null 2>&1; then
  ARCHIVE="$LOGS/archive-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$ARCHIVE"; mv "$LOGS"/*.log "$ARCHIVE"/
  echo "   moved to ${ARCHIVE#$ROOT/}"
fi

echo "== Starting services"
"$ROOT/scripts/start-all.sh"

echo "== Warming up (first request after a restart opens the throttle publisher connection)"
TOKEN=$("$ROOT/scripts/get-token.sh" PAYLANKA)
curl -sk -o /dev/null -w "   warm-up call: %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  -H "X-Correlation-ID: warmup-$(date +%s)" https://localhost:8243/lhb/accounts/v2/accounts/ACC-1001

echo "== Security suite"
if "$ROOT/scripts/security-tests.sh" | tail -1 | grep -q ' 0 failed'; then
  echo
  echo "READY: demo state restored and all security checks pass."
  echo "Reminders: run 'caffeinate -di' in a spare terminal; generate fresh tokens before the security segment."
else
  echo "NOT READY: security checks failed. Run scripts/security-tests.sh for details." >&2
  exit 1
fi

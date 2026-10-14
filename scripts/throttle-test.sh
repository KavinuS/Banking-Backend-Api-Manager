#!/usr/bin/env bash
# P6 / T11: QuickBudget (LHBPartnerDemo tier, 5 req/min) sends a steady stream well above quota.
# PASS when at least one HTTP 429 appears in the window AND access recovers after the window.
# Quota enforcement is asynchronous (gateway -> Traffic Manager -> JMS decision -> gateway). At 1 req/s the 6th call
# was rejected in 3/3 runs; at 2 req/s right after a sleep/wake ~10 calls passed first; a 1-second burst of 15 never
# tripped it (observed 2026-10-14). Instant spike protection is the job of burst control, not the quota.
# The Traffic Manager rule uses timeBatch(1 min, 0): quota windows reset on wall-clock minute boundaries,
# so the burst starts just after a boundary and recovery is checked just after the next one.
# Usage: throttle-test.sh [requests=20] [--no-recovery]   (requests are paced at 1 per second)
set -uo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
N=${1:-20}; RECOVERY=true; [ "${2:-}" = "--no-recovery" ] && RECOVERY=false
URL=https://localhost:8243/lhb/accounts/v2/accounts/ACC-2001
ACCESS_LOG="$ROOT/banking-backend/logs/access.$(date +%Y-%m-%d).log"
RUN_ID="thr-$(date +%s)"

TOKEN=$("$ROOT/scripts/get-token.sh" QUICKBUDGET) || exit 2
next_minute() { local sec; sec=$(date +%S); sec=${sec#0}; echo $(( 60 - sec + ${1:-0} )); }
wait_s=$(next_minute 2); echo "Waiting ${wait_s}s for a fresh quota window (minute boundary)..."; sleep "$wait_s"

codes=(); first429=""; body429=""
for i in $(seq 1 "$N"); do
  out=$(curl -sk -w '\n%{http_code}' -H "Authorization: Bearer $TOKEN" -H "X-Correlation-ID: $RUN_ID-$i" "$URL")
  code=${out##*$'\n'}; codes+=("$code")
  if [ "$code" = 429 ] && [ -z "$first429" ]; then first429=$i; body429=${out%$'\n'*}; fi
  sleep 1
done
reached=$(grep -c "cid=$RUN_ID-" "$ACCESS_LOG" 2>/dev/null || true); reached=${reached:-0}

echo "Tier: LHBPartnerDemo (5 requests/minute)   Sent: $N requests at 1/s"
echo "Status sequence: ${codes[*]}"
ok=$(printf '%s\n' "${codes[@]}" | grep -c '^200$'); thr=$(printf '%s\n' "${codes[@]}" | grep -c '^429$')
echo "200 OK: $ok   429 throttled: $thr   requests that reached the backend: $reached"
if [ -z "$first429" ]; then echo "FAIL  T11  no 429 within the burst"; exit 1; fi
echo "Quota of 5 exceeded from request #6; first 429 at request #$first429 (overshoot: $(( first429 - 6 )) calls)"
echo "429 body ->  $(jq -c '{code,message,description}' <<<"$body429" 2>/dev/null || echo "$body429")"
[ "$reached" -eq "$ok" ] && echo "Backend protected: only the $ok allowed calls reached the core system" \
  || echo "NOTE: backend saw $reached requests vs $ok allowed"

if $RECOVERY; then
  wait_s=$(next_minute 3); echo "Waiting ${wait_s}s for the next quota window..."
  sleep "$wait_s"
  rc=$(curl -sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$URL")
  [ "$rc" = 200 ] && echo "Recovery after window: $rc" || { echo "FAIL  T11  no recovery after window (got $rc)"; exit 1; }
fi
echo "PASS  T11"

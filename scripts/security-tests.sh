#!/usr/bin/env bash
# P5: automated security test matrix (PROJECT_PLAN §7). Prints PASS/FAIL per test, exits non-zero on any failure.
# Usage: security-tests.sh [--slow]   (--slow adds T5, which waits for a 10-second token to expire)
# Expected codes below were observed on this installation (see wso2/README.md), not assumed.
set -uo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GW=https://localhost:8243/lhb/accounts/v2        # current version (v1 is deprecated, see T13)
GW_V1=https://localhost:8243/lhb/accounts/v1
BE=http://127.0.0.1:8080
SLOW=false; [ "${1:-}" = "--slow" ] && SLOW=true
PASS=0; FAIL=0
BODIES="$(mktemp -d)"; trap 'rm -rf "$BODIES"' EXIT

# expect <id> <description> <expected-http> <expected-code|-> <curl args...>
# expected-code is matched against the JSON "code" field (WSO2 numeric code or backend code); "-" skips it.
expect() {
  local id=$1 desc=$2 want_http=$3 want_code=$4; shift 4
  local out="$BODIES/$id.json" http code
  http=$(curl -sk -m 15 -o "$out" -w '%{http_code}' "$@")
  code=$(jq -r '.code // empty' "$out" 2>/dev/null)
  if [ "$http" = "$want_http" ] && { [ "$want_code" = - ] || [ "$code" = "$want_code" ]; }; then
    printf "PASS  %-5s %-58s %s %s\n" "$id" "$desc" "$http" "$code"; PASS=$((PASS+1))
  else
    printf "FAIL  %-5s %-58s got %s %s, want %s %s\n" "$id" "$desc" "$http" "$code" "$want_http" "$want_code"; FAIL=$((FAIL+1))
  fi
}
bearer() { echo "Authorization: Bearer $1"; }

TOK_PAYLANKA=$("$ROOT/scripts/get-token.sh" PAYLANKA) || { echo "cannot get PayLanka token" >&2; exit 2; }
TOK_ROGUE=$("$ROOT/scripts/get-token.sh" ROGUEAPP)   || { echo "cannot get RogueApp token" >&2; exit 2; }
TOK_QB=$("$ROOT/scripts/get-token.sh" QUICKBUDGET)   || exit 2   # asks for both scopes; bank grants accounts:read only
# Tampered tokens. Never alter the LAST signature character: its low 4 bits are base64url padding, so some
# substitutions (e.g. A->B) decode to the identical signature and the token is still genuinely valid.
TAMPER_PY='
import base64, json, os, sys
h, p, s = os.environ["TOK"].split(".")
b64d = lambda x: base64.urlsafe_b64decode(x + "=" * (-len(x) % 4))
b64e = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
if sys.argv[1] == "claims":      # forge: extend expiry by a year, keep the original signature
    claims = json.loads(b64d(p)); claims["exp"] += 365 * 24 * 3600
    p = b64e(json.dumps(claims, separators=(",", ":")).encode())
else:                            # corrupt a byte in the middle of the signature
    sig = bytearray(b64d(s)); sig[len(sig) // 2] ^= 0xFF; s = b64e(bytes(sig))
print(".".join((h, p, s)))'
TOK_FORGED=$(TOK="$TOK_PAYLANKA" python3 -I -c "$TAMPER_PY" claims)
TOK_BADSIG=$(TOK="$TOK_PAYLANKA" python3 -I -c "$TAMPER_PY" signature)

echo "== Discovery & onboarding"
expect T1  "API listed in Developer Portal (anonymous browse)"  200 - \
  "https://localhost:9443/api/am/devportal/v3/apis?query=name:LHB-Accounts"
jq -e '.list[] | select(.name=="LHB-Accounts" and .version=="v2" and .lifeCycleStatus=="PUBLISHED")' "$BODIES/T1.json" >/dev/null \
  || { echo "FAIL  T1    LHB-Accounts v2 not PUBLISHED in listing"; FAIL=$((FAIL+1)); }

echo "== Authentication (gateway)"
expect T3  "No token -> rejected"                                401 900902 "$GW/accounts/ACC-1001"
expect T4a "Garbage token -> rejected"                           401 900901 -H "$(bearer abc.def.ghi)" "$GW/accounts/ACC-1001"
expect T4b "Forged claims (expiry +1 year), original signature"   401 900901 -H "$(bearer "$TOK_FORGED")" "$GW/accounts/ACC-1001"
expect T4c "Real token with corrupted signature byte"            401 900901 -H "$(bearer "$TOK_BADSIG")" "$GW/accounts/ACC-1001"

echo "== Authorization"
expect T7a "Subscribed app (PayLanka) -> account data"           200 - -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-1001"
jq -e '.maskedAccountNumber == "****1001" and (has("accountNumber") | not)' "$BODIES/T7a.json" >/dev/null \
  || { echo "FAIL  T7a   response not masked"; FAIL=$((FAIL+1)); }
expect T7c "Subscribed app -> transactions"                      200 - -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-1001/transactions?limit=5"
expect T12 "App with keys but NO subscription (RogueApp)"        403 900908 -H "$(bearer "$TOK_ROGUE")" "$GW/accounts/ACC-1001"

echo "== Account-level authorization / BOLA (B3)"
AUDIT_LOG="$ROOT/banking-backend/logs/audit.log"
BOLA_CID="bola-$(date +%s)"
expect T7b "QuickBudget -> its consented account ACC-2001"       200 - -H "$(bearer "$TOK_QB")" "$GW/accounts/ACC-2001"
expect T8a "QuickBudget -> PayLanka's ACC-1001 (BOLA attempt)"   404 NOT_FOUND -H "$(bearer "$TOK_QB")" -H "X-Correlation-ID: $BOLA_CID" "$GW/accounts/ACC-1001"
expect T8b "PayLanka -> QuickBudget's ACC-2001 transactions"     404 NOT_FOUND -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-2001/transactions"
if jq -s -e --arg c "$BOLA_CID" 'map(select(.correlationId==$c and .outcome=="DENIED_NO_CONSENT" and .application=="QuickBudget")) | length == 1' "$AUDIT_LOG" >/dev/null 2>&1; then
  printf "PASS  %-5s %-58s %s\n" T8c "BOLA attempt recorded in audit log (correlation ID)" "DENIED_NO_CONSENT"; PASS=$((PASS+1))
else
  echo "FAIL  T8c   no DENIED_NO_CONSENT audit entry for $BOLA_CID"; FAIL=$((FAIL+1))
fi
expect T8e "QuickBudget -> non-existent ACC-9999"                404 NOT_FOUND -H "$(bearer "$TOK_QB")" "$GW/accounts/ACC-9999"
if diff <(jq -S 'del(.correlationId)' "$BODIES/T8a.json") <(jq -S 'del(.correlationId)' "$BODIES/T8e.json") >/dev/null 2>&1; then
  printf "PASS  %-5s %-58s\n" T8d "Denied account response identical to unknown account"; PASS=$((PASS+1))
else
  echo "FAIL  T8d   BOLA 404 body differs from unknown-account 404"; FAIL=$((FAIL+1))
fi

echo "== Scopes / entitlements (B1)"
TOK_ACC_ONLY=$("$ROOT/scripts/get-token.sh" PAYLANKA "accounts:read")
TOK_NOSCOPE=$("$ROOT/scripts/get-token.sh" PAYLANKA none)
expect T6a "Token with accounts:read only -> transactions"      403 900910 -H "$(bearer "$TOK_ACC_ONLY")" "$GW/accounts/ACC-1001/transactions"
expect T6b "Token with no API scope -> account"                 403 900910 -H "$(bearer "$TOK_NOSCOPE")" "$GW/accounts/ACC-1001"
expect T6c "QuickBudget (not entitled) -> transactions"         403 900910 -H "$(bearer "$TOK_QB")" "$GW/accounts/ACC-2001/transactions"
QB_SCOPE=$(jq -rR 'split(".")[1] | gsub("-";"+") | gsub("_";"/") | @base64d | fromjson | .scope' <<<"$TOK_QB")
if [ "$QB_SCOPE" = "accounts:read" ]; then
  printf "PASS  %-5s %-58s %s\n" T6d "QuickBudget asked for transactions:read, was not granted" "[$QB_SCOPE]"; PASS=$((PASS+1))
else
  echo "FAIL  T6d   QuickBudget token scope is [$QB_SCOPE]"; FAIL=$((FAIL+1))
fi

echo "== Input validation & API surface"
expect T9a "Malformed account id"                                400 INVALID_REQUEST -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/abc"
expect T9b "Injection-style id (1;DROP)"                         400 INVALID_REQUEST -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/1%3BDROP"
expect T9c "limit=10000 (resource exhaustion)"                   400 INVALID_REQUEST -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-1001/transactions?limit=10000"
expect T9d "fromDate after toDate"                               400 INVALID_REQUEST -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-1001/transactions?fromDate=2026-09-30&toDate=2026-09-01"
expect T9e "Unknown account -> 404 (no enumeration detail)"      404 NOT_FOUND -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-9999"
expect T9f "Undeclared method (POST) blocked at gateway"         405 - -X POST -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-1001"
expect T9g "Undeclared resource blocked at gateway"              404 - -H "$(bearer "$TOK_PAYLANKA")" "$GW/admin/users"
expect T9h "Plain HTTP (8280) refused - HTTPS only"              403 - "http://localhost:8280/lhb/accounts/v2/accounts/ACC-1001"

echo "== Versioning & lifecycle (B4)"
expect T13a "v2 returns new field availableBalance"             200 - -H "$(bearer "$TOK_PAYLANKA")" "$GW/accounts/ACC-1001"
jq -e 'has("availableBalance")' "$BODIES/T13a.json" >/dev/null || { echo "FAIL  T13a  availableBalance missing on v2"; FAIL=$((FAIL+1)); }
expect T13b "Deprecated v1 still serves existing subscriber"    200 - -H "$(bearer "$TOK_PAYLANKA")" "$GW_V1/accounts/ACC-1001"
jq -e 'has("availableBalance") | not' "$BODIES/T13b.json" >/dev/null || { echo "FAIL  T13b  v1 contract changed"; FAIL=$((FAIL+1)); }
if jq -e '[.list[] | select(.name=="LHB-Accounts" and .version=="v1")] | length == 0' "$BODIES/T1.json" >/dev/null; then
  printf "PASS  %-5s %-58s\n" T13c "Deprecated v1 hidden from Developer Portal"; PASS=$((PASS+1))
else
  echo "FAIL  T13c  v1 still listed in Developer Portal"; FAIL=$((FAIL+1))
fi
expect T13d "BOLA protection identical on v2"                   404 NOT_FOUND -H "$(bearer "$TOK_QB")" "$GW/accounts/ACC-1001"

echo "== Gateway bypass (direct to backend)"
expect T10a "Direct call, no credential"                         401 UNAUTHORIZED "$BE/accounts/ACC-1001"
expect T10b "Direct call, wrong credential"                      401 UNAUTHORIZED -u "${LHB_GW_USER}:not-the-gateway-password" "$BE/accounts/ACC-1001"
expect T10c "Partner token sent directly to backend"             401 UNAUTHORIZED -H "$(bearer "$TOK_PAYLANKA")" "$BE/accounts/ACC-1001"
expect T10e "Direct call with valid Basic credential, no gateway JWT" 401 UNAUTHORIZED -u "${LHB_GW_USER}:${LHB_GW_PASS}" "$BE/accounts/ACC-1001"
FAKE_JWT=$(TOK="$TOK_PAYLANKA" python3 -I -c "$TAMPER_PY" signature)   # gateway-shaped but not gateway-signed
expect T10f "Direct call, Basic credential + forged assertion"    401 UNAUTHORIZED -u "${LHB_GW_USER}:${LHB_GW_PASS}" -H "X-JWT-Assertion: $FAKE_JWT" "$BE/accounts/ACC-1001"
# Partner access tokens and backend assertions are signed by the same WSO2 key: the issuer/claim checks must stop this
expect T10g "Direct call, Basic credential + partner ACCESS token"  401 UNAUTHORIZED -u "${LHB_GW_USER}:${LHB_GW_PASS}" -H "X-JWT-Assertion: $TOK_PAYLANKA" "$BE/accounts/ACC-1001"
IP=$(ipconfig getifaddr en0 2>/dev/null || true)
if [ -n "$IP" ]; then
  if curl -s -m 3 -o /dev/null "http://$IP:8080/actuator/health"; then
    echo "FAIL  T10d  Backend reachable on LAN IP $IP"; FAIL=$((FAIL+1))
  else
    printf "PASS  %-5s %-58s %s\n" T10d "Backend not reachable from network (LAN IP)" "refused"; PASS=$((PASS+1))
  fi
fi

if $SLOW; then
  echo "== Token expiry (10-second token, waiting 20s)"
  TOK_SHORT=$("$ROOT/scripts/get-token.sh" EXPIRYTEST)
  expect T5a "Short-lived token while valid (unsubscribed app)" 403 900908 -H "$(bearer "$TOK_SHORT")" "$GW/accounts/ACC-1001"
  sleep 20
  expect T5b "Same token after expiry -> rejected as invalid"     401 900901 -H "$(bearer "$TOK_SHORT")" "$GW/accounts/ACC-1001"
fi

echo "== Leakage (T14): every error body above"
LEAKS=$(grep -lE 'Exception|stack|trace"|java\.|127\.0\.0\.1|:8080|LHB0000' "$BODIES"/*.json 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')
if [ -z "$LEAKS" ]; then
  printf "PASS  %-5s %-58s\n" T14 "No stack traces, internal hosts or full account numbers"; PASS=$((PASS+1))
else
  echo "FAIL  T14   leakage in: $LEAKS"; FAIL=$((FAIL+1))
fi

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]

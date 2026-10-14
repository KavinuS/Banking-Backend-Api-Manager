#!/usr/bin/env bash
# P4: onboard a (fictional) partner through the Developer Portal REST API (v3), acting as the partner's developer user.
# Usage: onboard-partner.sh <dev-user> "<Application Name>" <ENV_PREFIX> <subscription-tier|none>
#   e.g. onboard-partner.sh paylanka_dev "PayLanka Wallet" PAYLANKA Unlimited
# Creates the application, optionally subscribes it to LHB-Accounts v1, generates PRODUCTION client-credentials keys
# and writes them to ~/.lhb-poc.env as LHB_<ENV_PREFIX>_ID / LHB_<ENV_PREFIX>_SECRET. Secrets are never printed.
# Optional env LHB_TOKEN_EXPIRY=<seconds> sets the application access-token lifetime (used for the expiry test app).
set -euo pipefail
[ $# -eq 4 ] || { echo "usage: $0 <dev-user> <app-name> <ENV_PREFIX> <tier|none>" >&2; exit 1; }
DEV_USER=$1; APP_NAME=$2; PREFIX=$3; TIER=$4
ENV_FILE=~/.lhb-poc.env
source "$ENV_FILE"
KM=https://localhost:9443
D=$KM/api/am/devportal/v3
START=$(date +%s)

pass_var="LHB_$(tr a-z A-Z <<<"$DEV_USER")_PASS"
id_var="LHB_${PREFIX}_ID"
[ -n "${!pass_var:-}" ] || { echo "$pass_var not set in $ENV_FILE" >&2; exit 1; }

# 1. Developer signs in (DCR client + password grant with Dev Portal scopes)
DCR=$(curl -sfk -u "$DEV_USER:${!pass_var}" -H 'Content-Type: application/json' \
  -d "{\"callbackUrl\":\"https://localhost\",\"clientName\":\"${DEV_USER}_cli\",\"owner\":\"$DEV_USER\",\"grantType\":\"password refresh_token\",\"saasApp\":true}" \
  "$KM/client-registration/v0.17/register")
T=$(curl -sfk -u "$(jq -r .clientId <<<"$DCR"):$(jq -r .clientSecret <<<"$DCR")" -d grant_type=password \
  --data-urlencode "username=$DEV_USER" --data-urlencode "password=${!pass_var}" \
  --data-urlencode "scope=apim:subscribe apim:app_manage apim:sub_manage" "$KM/oauth2/token" | jq -r .access_token)
AUTH=(-H "Authorization: Bearer $T" -H 'Content-Type: application/json')

# 2. Discover the API (as a partner would in the portal)
API_ID=$(curl -sfk "${AUTH[@]}" "$D/apis?query=name:LHB-Accounts%20version:v1" | jq -r '.list[0].id // empty')
[ -n "$API_ID" ] || { echo "LHB-Accounts v1 not visible in Developer Portal" >&2; exit 1; }
echo "[$DEV_USER] discovered LHB-Accounts v1 ($API_ID)"

# 3. Application (reuse if it already exists)
APP_ID=$(curl -sfk "${AUTH[@]}" "$D/applications?query=$(jq -rn --arg n "$APP_NAME" '$n|@uri')" \
  | jq -r --arg n "$APP_NAME" '.list[] | select(.name==$n) | .applicationId' | head -1)
if [ -z "$APP_ID" ]; then
  APP_ID=$(curl -sfk "${AUTH[@]}" -d "$(jq -n --arg n "$APP_NAME" \
      '{name:$n, throttlingPolicy:"Unlimited", tokenType:"JWT", description:("Fictional partner application: " + $n)}')" \
    "$D/applications" | jq -r .applicationId)
  echo "[$DEV_USER] created application '$APP_NAME' ($APP_ID)"
else
  echo "[$DEV_USER] application '$APP_NAME' already exists ($APP_ID)"
fi

# 4. Subscription
if [ "$TIER" != none ]; then
  SUB=$(curl -sfk "${AUTH[@]}" "$D/subscriptions?applicationId=$APP_ID" | jq -r --arg a "$API_ID" '.list[] | select(.apiId==$a) | .status' | head -1)
  if [ -z "$SUB" ]; then
    SUB=$(curl -sfk "${AUTH[@]}" -d "{\"applicationId\":\"$APP_ID\",\"apiId\":\"$API_ID\",\"throttlingPolicy\":\"$TIER\"}" \
      "$D/subscriptions" | jq -r .status)
  fi
  echo "[$DEV_USER] subscription to LHB-Accounts v1 on tier '$TIER': $SUB"
else
  echo "[$DEV_USER] no subscription (by design)"
fi

# 5. Production keys (client credentials only), stored straight into the env file
if [ -n "${!id_var:-}" ]; then
  echo "[$DEV_USER] $id_var already set; keeping existing keys"
else
  KEY_REQ=$(jq -n --arg exp "${LHB_TOKEN_EXPIRY:-3600}" '{keyType:"PRODUCTION", grantTypesToBeSupported:["client_credentials"],
    keyManager:"Resident Key Manager", additionalProperties:{application_access_token_expiry_time:$exp}}')
  KEYS=$(curl -sfk "${AUTH[@]}" -d "$KEY_REQ" \
    "$D/applications/$APP_ID/generate-keys")
  LHB_NEW_ID=$(jq -r .consumerKey <<<"$KEYS") LHB_NEW_SECRET=$(jq -r .consumerSecret <<<"$KEYS") \
  LHB_PREFIX=$PREFIX LHB_ENV_FILE=$ENV_FILE python3 -I -c '
import os, re
p, pre = os.environ["LHB_ENV_FILE"], os.environ["LHB_PREFIX"]
s = open(p).read()
for suffix, val in (("ID", os.environ["LHB_NEW_ID"]), ("SECRET", os.environ["LHB_NEW_SECRET"])):
    assert val and val != "null", "key generation failed"
    line = f"export LHB_{pre}_{suffix}=\"{val}\""
    s, n = re.subn(rf"^export LHB_{pre}_{suffix}=.*$", line, s, flags=re.M)
    if n == 0: s += "\n" + line
open(p, "w").write(s)'
  echo "[$DEV_USER] PRODUCTION keys generated (client_credentials) and saved to $ENV_FILE as LHB_${PREFIX}_ID/SECRET"
fi
echo "[$DEV_USER] onboarding steps took $(( $(date +%s) - START ))s"

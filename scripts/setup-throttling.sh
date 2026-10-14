#!/usr/bin/env bash
# P6: create the LHBPartnerDemo subscription tier (5 req/min, hard stop), allow it on LHB-Accounts v1,
# redeploy, and subscribe QuickBudget on it. Safe to re-run.
# WSO2 rejects '-' in policy names (code 900706), so the display name "LHB Partner Demo" carries the readable form.
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
TIER=LHBPartnerDemo

# 1. Admin: subscription policy (Admin Portal -> Rate Limiting Policies -> Subscription Policies)
AT=$(apim_token admin "$LHB_ADMIN_PASS" "apim:admin apim:tier_view apim:tier_manage")
A=$KM/api/am/admin/v4
if curl -sfk -H "Authorization: Bearer $AT" "$A/throttling/policies/subscription" | jq -e --arg t "$TIER" '.list[] | select(.policyName==$t)' >/dev/null; then
  echo "Policy $TIER already exists"
else
  curl -sfk -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -o /dev/null "$A/throttling/policies/subscription" -d "$(jq -n --arg t "$TIER" '{
    policyName: $t, displayName: "LHB Partner Demo",
    description: "POC partner tier: 5 requests per minute. Burst control off so the quota itself is demonstrated.",
    defaultLimit: {type: "REQUESTCOUNTLIMIT", requestCount: {timeUnit: "min", unitTime: 1, requestCount: 5}},
    rateLimitCount: 0, rateLimitTimeUnit: "sec", stopOnQuotaReach: true, billingPlan: "FREE"}')"
  echo "Created subscription policy $TIER (5 req/min, stopOnQuotaReach)"
fi

# 2. Publisher: allow the tier on the API, then redeploy
PT=$(apim_token lhb_publisher "$LHB_PUBLISHER_PASS" "apim:api_view apim:api_create apim:api_publish")
P=$KM/api/am/publisher/v4
ID=$(api_id "$PT" LHB-Accounts v1)
API=$(curl -sfk -H "Authorization: Bearer $PT" "$P/apis/$ID")
if jq -e --arg t "$TIER" '.policies | index($t)' <<<"$API" >/dev/null; then
  echo "API already offers $TIER"
else
  jq --arg t "$TIER" '.policies += [$t]' <<<"$API" \
    | curl -sfk -X PUT -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' --data @- "$P/apis/$ID" -o /dev/null
  echo "API business plans: $(curl -sfk -H "Authorization: Bearer $PT" "$P/apis/$ID" | jq -c .policies)"
  redeploy "$PT" "$ID" "P6: add $TIER business plan"
fi

# 3. Developer Portal: QuickBudget subscribes on the demo tier
"$ROOT/scripts/onboard-partner.sh" quickbudget_dev "QuickBudget" QUICKBUDGET "$TIER" | grep -E 'subscription|keeping'

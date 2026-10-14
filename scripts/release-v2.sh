#!/usr/bin/env bash
# P10 (B4): release LHB-Accounts v2 (adds availableBalance) and deprecate v1. Safe to re-run.
# Observed on 4.7.0: replacing the OpenAPI definition WIPES per-operation scopes, so they are re-applied afterwards.
# Publishing v2 auto-subscribes existing v1 subscribers (same tiers) unless "Requires re-subscription" is chosen.
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
P=$KM/api/am/publisher/v4
PT=$(apim_token lhb_publisher "$LHB_PUBLISHER_PASS" "apim:api_view apim:api_create apim:api_publish")
V1=$(api_id "$PT" LHB-Accounts v1)
V2=$(api_id "$PT" LHB-Accounts v2)

if [ -z "$V2" ]; then
  V2=$(curl -sfk -X POST -H "Authorization: Bearer $PT" "$P/apis/copy-api?newVersion=v2&apiId=$V1&defaultVersion=false" | jq -r .id)
  echo "Created v2 ($V2) from v1"
  curl -sfk -X PUT -H "Authorization: Bearer $PT" \
    -F "apiDefinition=<$ROOT/banking-backend/src/main/resources/openapi/lhb-accounts-v2.yaml" "$P/apis/$V2/swagger" -o /dev/null
  echo "Replaced OpenAPI definition with lhb-accounts-v2.yaml"
fi

# Scopes, /v2 backend path and description (re-applied every run: the definition update clears scopes)
curl -sfk -H "Authorization: Bearer $PT" "$P/apis/$V2" | jq '
  .scopes = [
    {shared:false, scope:{name:"accounts:read", displayName:"Read account summary",
      description:"Masked account summary and balances", bindings:["Internal/subscriber"]}},
    {shared:false, scope:{name:"transactions:read", displayName:"Read transaction history",
      description:"Transaction history (bank-granted entitlement)", bindings:["Internal/lhb_txn_partner"]}}]
  | .operations |= map(.scopes = (if (.target | endswith("/transactions")) then ["transactions:read"] else ["accounts:read"] end))
  | .endpointConfig.production_endpoints.url = "http://127.0.0.1:8080/v2"
  | .endpointConfig.sandbox_endpoints.url = "http://127.0.0.1:8080/v2"
  | .description = "Lanka Horizon Bank partner API v2: adds availableBalance (fictional data)."' \
  | curl -sfk -X PUT -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' --data @- "$P/apis/$V2" \
  | jq -c '{v2_scopes: ([.operations[] | {(.target): .scopes}] | add), endpoint: .endpointConfig.production_endpoints.url}'

STATE=$(curl -sfk -H "Authorization: Bearer $PT" "$P/apis/$V2" | jq -r .lifeCycleStatus)
if [ "$STATE" != PUBLISHED ]; then
  redeploy "$PT" "$V2" "P10: v2 (availableBalance)"
  curl -sfk -X POST -H "Authorization: Bearer $PT" "$P/apis/change-lifecycle?apiId=$V2&action=Publish" | jq -r '"v2: " + .lifecycleState.state'
else
  redeploy "$PT" "$V2" "P10: v2 scopes re-applied"
  echo "v2: already Published"
fi

V1_STATE=$(curl -sfk -H "Authorization: Bearer $PT" "$P/apis/$V1" | jq -r .lifeCycleStatus)
if [ "$V1_STATE" = PUBLISHED ]; then
  curl -sfk -X POST -H "Authorization: Bearer $PT" "$P/apis/change-lifecycle?apiId=$V1&action=Deprecate" | jq -r '"v1: " + .lifecycleState.state'
else
  echo "v1: $V1_STATE"
fi

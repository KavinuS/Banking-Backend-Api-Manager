#!/usr/bin/env bash
# P11 (B5): gateway threat protection on LHB-Accounts v2. Safe to re-run.
#  - Built-in regexPolicy_v1 (SQL-injection patterns over body, headers, path) on every operation's request flow
#  - API-level CORS: only the (fictional) PayLanka web origin, GET only; overrides the global allow_origins="*"
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
P=$KM/api/am/publisher/v4
PT=$(apim_token lhb_publisher "$LHB_PUBLISHER_PASS" "apim:api_view apim:api_create apim:api_publish apim:common_operation_policy_view")
V2=$(api_id "$PT" LHB-Accounts v2)
REGEX_ID=$(curl -sfk -H "Authorization: Bearer $PT" "$P/operation-policies?limit=100" | jq -r '.list[] | select(.name=="regexPolicy" and .version=="v1") | .id')

curl -sfk -H "Authorization: Bearer $PT" "$P/apis/$V2" | jq --arg rid "$REGEX_ID" '
  .operations |= map(.operationPolicies = {
      request:  [{policyName:"regexPolicy", policyVersion:"v1", policyId:$rid, parameters:{}}],
      response: [], fault: []})
  | .corsConfiguration = {
      corsConfigurationEnabled: true,
      accessControlAllowOrigins: ["https://app.paylanka.example"],
      accessControlAllowCredentials: false,
      accessControlAllowHeaders: ["authorization", "content-type", "x-correlation-id"],
      accessControlAllowMethods: ["GET", "OPTIONS"]}' \
  | curl -sfk -X PUT -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' --data @- "$P/apis/$V2" \
  | jq -c '{request_policies: ([.operations[] | {(.target): [.operationPolicies.request[].policyName]}] | add),
            cors: .corsConfiguration.accessControlAllowOrigins,
            scopes_intact: ([.operations[].scopes | length] | all(. == 1))}'
redeploy "$PT" "$V2" "P11: regex threat protection + restricted CORS"

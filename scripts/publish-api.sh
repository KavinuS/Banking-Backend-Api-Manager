#!/usr/bin/env bash
# P3: import, secure, deploy and publish LHB-Accounts v1 via the Publisher REST API (v4),
# acting as the least-privilege user lhb_publisher. Safe to re-run: exits if the API already exists.
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPEC="$ROOT/banking-backend/src/main/resources/openapi/lhb-accounts-v1.yaml"
KM=https://localhost:9443
P=$KM/api/am/publisher/v4
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# 1. OAuth client for the CLI (DCR) + token with publisher scopes
DCR=$(curl -sfk -u "lhb_publisher:${LHB_PUBLISHER_PASS}" -H 'Content-Type: application/json' \
  -d '{"callbackUrl":"https://localhost","clientName":"lhb_publisher_cli","owner":"lhb_publisher","grantType":"password refresh_token","saasApp":true}' \
  "$KM/client-registration/v0.17/register")
CID=$(jq -r .clientId <<<"$DCR"); CS=$(jq -r .clientSecret <<<"$DCR")
T=$(curl -sfk -u "$CID:$CS" -d grant_type=password --data-urlencode username=lhb_publisher \
  --data-urlencode "password=${LHB_PUBLISHER_PASS}" \
  --data-urlencode "scope=apim:api_view apim:api_create apim:api_publish" "$KM/oauth2/token" | jq -r .access_token)
AUTH=(-H "Authorization: Bearer $T")

EXISTING=$(curl -sfk "${AUTH[@]}" "$P/apis?query=name:LHB-Accounts%20version:v1" | jq -r '.list[0].id // empty')
if [ -n "$EXISTING" ]; then echo "LHB-Accounts v1 already exists ($EXISTING), nothing to do."; exit 0; fi

# 2. Import OpenAPI (HTTPS only, Unlimited tier for now; demo tier is added in P6)
PROPS='{"name":"LHB-Accounts","version":"v1","context":"/lhb/accounts",
 "description":"Lanka Horizon Bank partner API: account information and transaction history (fictional data).",
 "tags":["banking","accounts"],"policies":["Unlimited"],"transport":["https"],
 "endpointConfig":{"endpoint_type":"http","production_endpoints":{"url":"http://127.0.0.1:8080"},"sandbox_endpoints":{"url":"http://127.0.0.1:8080"}}}'
ID=$(curl -sfk "${AUTH[@]}" -F "file=@$SPEC" -F "additionalProperties=$PROPS" "$P/apis/import-openapi" | jq -r .id)
echo "Imported API id: $ID"

# 3. Basic-auth endpoint security (gateway -> backend credential)
curl -sfk "${AUTH[@]}" "$P/apis/$ID" \
  | jq --arg u "$LHB_GW_USER" --arg p "$LHB_GW_PASS" '.endpointConfig.endpoint_security = {
      production: {enabled:true, type:"BASIC", username:$u, password:$p},
      sandbox:    {enabled:true, type:"BASIC", username:$u, password:$p}}' > "$WORK/api.json"
curl -sfk -X PUT "${AUTH[@]}" -H 'Content-Type: application/json' --data @"$WORK/api.json" "$P/apis/$ID" -o /dev/null
echo "Endpoint security: BASIC (production + sandbox)"

# 4. Revision -> deploy to the Default gateway -> publish
RID=$(curl -sfk "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"description":"Initial v1 with Basic endpoint security"}' \
  "$P/apis/$ID/revisions" | jq -r .id)
curl -sfk "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '[{"name":"Default","vhost":"localhost","displayOnDevportal":true}]' "$P/apis/$ID/deploy-revision?revisionId=$RID" -o /dev/null
curl -sfk -X POST "${AUTH[@]}" "$P/apis/change-lifecycle?apiId=$ID&action=Publish" | jq -r '"Lifecycle: " + .lifecycleState.state'

# 5. Gateway deployment is asynchronous (~1-5 s): wait until it answers 401 (token required) instead of 404
for _ in $(seq 1 30); do
  code=$(curl -sk -o /dev/null -w '%{http_code}' https://localhost:8243/lhb/accounts/v1/accounts/ACC-1001)
  [ "$code" = 401 ] && { echo "Gateway ready: no-token call -> 401"; exit 0; }
  sleep 1
done
echo "Gateway did not become ready (last status $code)" >&2; exit 1

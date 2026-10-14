#!/usr/bin/env bash
# P7 (B1): resource-level OAuth2 scopes on LHB-Accounts v1. Safe to re-run.
#   accounts:read      -> bound to Internal/subscriber (any approved partner)
#   transactions:read  -> bound to Internal/lhb_txn_partner (bank-granted entitlement: PayLanka only)
# With client_credentials, WSO2 issues a scope only if the app OWNER holds a bound role, so entitlement is
# controlled by the bank through role membership, not by what the partner asks for.
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib.sh"
UM="$KM/services/RemoteUserStoreManagerService"
TXN_ROLE=Internal/lhb_txn_partner

soap() { # soap <action> <body-xml>
  curl -sk -u "admin:$LHB_ADMIN_PASS" -H 'Content-Type: text/xml' -H "SOAPAction: urn:$1" \
    --data "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ser=\"http://service.ws.um.carbon.wso2.org\"><soapenv:Body>$2</soapenv:Body></soapenv:Envelope>" "$UM"
}

# 1. Entitlement role, granted to PayLanka's developer only
if soap isExistingRole "<ser:isExistingRole><ser:roleName>$TXN_ROLE</ser:roleName></ser:isExistingRole>" | grep -q '<ns:return>true'; then
  echo "Role $TXN_ROLE exists"
else
  soap addRole "<ser:addRole><ser:roleName>$TXN_ROLE</ser:roleName><ser:userList>paylanka_dev</ser:userList></ser:addRole>" >/dev/null
  echo "Created role $TXN_ROLE with member paylanka_dev"
fi
echo "Members of $TXN_ROLE: $(soap getUserListOfRole "<ser:getUserListOfRole><ser:roleName>$TXN_ROLE</ser:roleName></ser:getUserListOfRole>" \
  | grep -oE '<ns:return>[^<]+' | sed 's/<ns:return>//' | tr '\n' ' ')"

# 2. Local scopes on the API + per-operation assignment, then redeploy
PT=$(apim_token lhb_publisher "$LHB_PUBLISHER_PASS" "apim:api_view apim:api_create apim:api_publish")
P=$KM/api/am/publisher/v4
ID=$(api_id "$PT" LHB-Accounts v1)
API=$(curl -sfk -H "Authorization: Bearer $PT" "$P/apis/$ID")
if jq -e '[.operations[].scopes[]?] | length == 2' <<<"$API" >/dev/null; then
  echo "Scopes already assigned: $(jq -c '[.operations[] | {(.target): .scopes}] | add' <<<"$API")"
  exit 0
fi
jq --arg txn "$TXN_ROLE" '
  .scopes = [
    {shared:false, scope:{name:"accounts:read", displayName:"Read account summary",
      description:"Masked account summary and ledger balance", bindings:["Internal/subscriber"]}},
    {shared:false, scope:{name:"transactions:read", displayName:"Read transaction history",
      description:"Transaction history (bank-granted entitlement)", bindings:[$txn]}}]
  | .operations |= map(.scopes = (if (.target | endswith("/transactions")) then ["transactions:read"] else ["accounts:read"] end))
' <<<"$API" | curl -sfk -X PUT -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' --data @- "$P/apis/$ID" -o "$ROOT/.scopes-put.json" \
  || { echo "API update failed"; exit 1; }
jq -c '[.operations[] | {(.verb + " " + .target): .scopes}] | add' "$ROOT/.scopes-put.json"; rm -f "$ROOT/.scopes-put.json"
redeploy "$PT" "$ID" "P7: resource scopes accounts:read / transactions:read"

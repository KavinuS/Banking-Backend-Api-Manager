#!/usr/bin/env bash
# Usage: get-token.sh <APP_PREFIX> [scopes|none]   e.g. get-token.sh PAYLANKA "accounts:read"
# Prints only the access token (client-credentials grant), reading the app's keys from ~/.lhb-poc.env.
# Default asks for all API scopes; WSO2 grants only those the app owner's roles are entitled to. "none" = no scope.
set -euo pipefail
source ~/.lhb-poc.env
app=$(tr a-z A-Z <<<"$1"); scope=${2:-accounts:read transactions:read}; [ "$scope" = none ] && scope=""
id_var="LHB_${app}_ID"; sec_var="LHB_${app}_SECRET"
[ -n "${!id_var:-}" ] || { echo "$id_var not set" >&2; exit 1; }
args=(-d grant_type=client_credentials)
[ -n "$scope" ] && args+=(--data-urlencode "scope=$scope")
resp=$(curl -sk -u "${!id_var}:${!sec_var}" "${args[@]}" https://localhost:9443/oauth2/token)
tok=$(jq -r '.access_token // empty' <<<"$resp")
[ -n "$tok" ] || { echo "token request failed: $(jq -c '{error,error_description}' <<<"$resp")" >&2; exit 1; }
echo "$tok"

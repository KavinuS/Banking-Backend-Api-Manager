# Shared helpers for the LHB POC scripts. Source after ~/.lhb-poc.env.
KM=https://localhost:9443

# apim_token <user> <password> "<scopes>"  -> prints an access token for the WSO2 REST APIs (DCR + password grant)
apim_token() {
  local user=$1 pass=$2 scopes=$3 dcr
  dcr=$(curl -sfk -u "$user:$pass" -H 'Content-Type: application/json' \
    -d "{\"callbackUrl\":\"https://localhost\",\"clientName\":\"${user}_cli\",\"owner\":\"$user\",\"grantType\":\"password refresh_token\",\"saasApp\":true}" \
    "$KM/client-registration/v0.17/register") || { echo "DCR failed for $user" >&2; return 1; }
  curl -sfk -u "$(jq -r .clientId <<<"$dcr"):$(jq -r .clientSecret <<<"$dcr")" -d grant_type=password \
    --data-urlencode "username=$user" --data-urlencode "password=$pass" --data-urlencode "scope=$scopes" \
    "$KM/oauth2/token" | jq -er .access_token
}

# api_id <publisher-token> <name> <version>
api_id() {
  curl -sfk -H "Authorization: Bearer $1" "$KM/api/am/publisher/v4/apis?query=name:$2%20version:$3" | jq -r '.list[0].id // empty'
}

# redeploy <publisher-token> <api-id> "<description>"  -> new revision deployed to the Default gateway, waits until live
redeploy() {
  local t=$1 id=$2 desc=$3 rid
  rid=$(curl -sfk -H "Authorization: Bearer $t" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg d "$desc" '{description:$d}')" "$KM/api/am/publisher/v4/apis/$id/revisions" | jq -er .id) || return 1
  curl -sfk -H "Authorization: Bearer $t" -H 'Content-Type: application/json' \
    -d '[{"name":"Default","vhost":"localhost","displayOnDevportal":true}]' \
    "$KM/api/am/publisher/v4/apis/$id/deploy-revision?revisionId=$rid" -o /dev/null || return 1
  sleep 6   # gateway deployment is asynchronous (observed 1-6 s)
  echo "Deployed revision $rid"
}

#!/usr/bin/env bash
# P11 (B6): show one partner request end to end by its correlation ID.
# Usage: trace.sh <correlation-id>        e.g. trace.sh paylanka-demo-001
#   or:  trace.sh --last                  most recent audit entry
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AUDIT="$ROOT/banking-backend/logs/audit.log"
[ $# -eq 1 ] || { echo "usage: $0 <correlation-id>|--last" >&2; exit 1; }
CID=$1
[ "$CID" = --last ] && CID=$(tail -1 "$AUDIT" | jq -r .correlationId)

echo "Correlation ID: $CID"
echo
echo "1. Backend access log (requests that actually reached the core system):"
grep -h "cid=$CID" "$ROOT"/banking-backend/logs/access.*.log 2>/dev/null | sed 's/^/   /' || echo "   (none: the gateway stopped it, or it never arrived)"
echo
echo "2. Authorization decision (audit trail):"
jq -c --arg c "$CID" 'select(.correlationId==$c) | {ts, application, operation, accountId, outcome}' "$AUDIT" 2>/dev/null | sed 's/^/   /' \
  | grep . || echo "   (no audit entry)"
echo
echo "3. Gateway security events around that time (WSO2 log, last 5):"
grep -E "APIAuthenticationHandler|Throttl|Threat" "$WSO2_HOME/repository/logs/wso2carbon.log" | tail -5 \
  | sed -E 's/^TID: \[[^]]*\] \[[^]]*\] \[([^]]*)\][^-]*- /   \1  /' | cut -c1-200

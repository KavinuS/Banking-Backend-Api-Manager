#!/usr/bin/env bash
# Stop the banking backend and WSO2 API Manager.
set -uo pipefail
source ~/.lhb-poc.env
pkill -f 'com.lhb.banking' && echo "Backend stopped" || echo "Backend not running"
"$WSO2_HOME/bin/api-manager.sh" stop
for _ in $(seq 1 30); do
  lsof -nP -iTCP:9443 -sTCP:LISTEN >/dev/null 2>&1 || { echo "WSO2 stopped"; exit 0; }
  sleep 2
done
echo "WSO2 still listening on 9443 after 60s" >&2; exit 1

#!/usr/bin/env bash
# Start WSO2 API Manager (and the banking backend once it exists) and wait until ready.
set -euo pipefail
source ~/.lhb-poc.env
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$WSO2_HOME/repository/logs/wso2carbon.log"

if lsof -nP -iTCP:9443 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "WSO2 already running on 9443"
else
  "$WSO2_HOME/bin/api-manager.sh" start
  echo -n "Waiting for WSO2"
  for _ in $(seq 1 150); do
    curl -sk -o /dev/null -w '%{http_code}' https://localhost:9443/publisher/ 2>/dev/null | grep -q 200 && break
    echo -n "."; sleep 2
  done
  echo
fi

if [ -x "$ROOT/banking-backend/mvnw" ]; then
  if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Backend already running on 8080"
  else
    (cd "$ROOT/banking-backend" && nohup ./mvnw -q spring-boot:run > backend.log 2>&1 &)
    echo -n "Waiting for backend"
    for _ in $(seq 1 60); do
      curl -s http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q UP && break
      echo -n "."; sleep 2
    done
    echo
  fi
fi

for p in publisher devportal admin; do
  printf "%-10s %s\n" "$p" "$(curl -sk -o /dev/null -w '%{http_code}' https://localhost:9443/$p/)"
done
printf "%-10s %s\n" gateway "$(curl -sk -o /dev/null -w '%{http_code}' https://localhost:8243/)"
printf "%-10s %s\n" backend "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -v '^000$' || echo down)"
LAST_START=$(grep -n 'WSO2 Carbon started in' "$LOG" | tail -1 | cut -d: -f1)
echo "WSO2 errors since last start: $(awk -v s="${LAST_START:-0}" 'NR>s' "$LOG" | grep -c ' ERROR ' || true)"

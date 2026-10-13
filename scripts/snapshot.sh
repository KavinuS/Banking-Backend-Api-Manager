#!/usr/bin/env bash
# Usage: snapshot.sh <label>  — stops WSO2 and archives the whole install (config + H2 databases).
set -euo pipefail
source ~/.lhb-poc.env
[ $# -eq 1 ] || { echo "usage: $0 <label>" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/stop-all.sh"
OUT="$ROOT/snapshots/wso2-$1-$(date +%Y%m%d-%H%M).tgz"
tar -czf "$OUT" -C "$(dirname "$WSO2_HOME")" "$(basename "$WSO2_HOME")"
echo "Snapshot: $OUT ($(du -h "$OUT" | cut -f1))"

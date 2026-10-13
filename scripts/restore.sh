#!/usr/bin/env bash
# Usage: restore.sh <snapshot.tgz>  — replaces the WSO2 install with a snapshot (current one kept aside).
set -euo pipefail
source ~/.lhb-poc.env
[ $# -eq 1 ] && [ -f "$1" ] || { echo "usage: $0 <snapshot.tgz>" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/stop-all.sh" || true
ASIDE="$WSO2_HOME.replaced-$(date +%Y%m%d-%H%M%S)"
mv "$WSO2_HOME" "$ASIDE"
tar -xzf "$1" -C "$(dirname "$WSO2_HOME")"
echo "Restored $1 (previous install moved to $ASIDE)"

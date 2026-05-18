#!/usr/bin/env bash
set -euo pipefail
PATTERN='SECRET|PASSWORD|API_KEY|JWT_SECRET|APIKEY|ACCESS_TOKEN|REFRESH_TOKEN|token|passwd|pwd'
echo "Scanning repository for likely secrets (pattern=$PATTERN)"
if command -v git >/dev/null 2>&1; then
  git --no-pager grep -nE "$PATTERN" || true
else
  grep -RInE "$PATTERN" . || true
fi

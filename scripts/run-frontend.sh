#!/bin/bash
set -e
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/run-frontend-only.sh" "$@"

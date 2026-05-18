#!/bin/sh
# Simple update swap script with verification and rollback
# Usage: perform-update.sh <target> <next> <backup> [verifySeconds]

VERIFY=${4:-30}

sleep 8

cp -f "$2" "$1" || { echo "copy_failed"; exit 2; }

if echo "$1" | grep -qi '\.jar$'; then
  JAVA=$(command -v java || true)
  if [ -z "$JAVA" ]; then echo 'JAVA_NOT_FOUND'; exit 3; fi
  "$JAVA" -jar "$1" &
  PID=$!
else
  "$1" &
  PID=$!
fi

sleep $VERIFY

if ! kill -0 $PID 2>/dev/null; then
  echo 'new_process_failed'
  cp -f "$3" "$1" || echo 'restore_failed'
  if echo "$1" | grep -qi '\.jar$'; then
    "$JAVA" -jar "$1" &
  else
    "$1" &
  fi
  exit 1
fi

rm -f "$2"
echo 'update_success'
exit 0

#!/bin/bash
# Concurrency stress test for the booking API.
#
# N users try to book the SAME single free slot at the same moment.
# PASS = exactly 1 booking exists for that slot afterwards (nobody was double-booked).
#
# Usage:  ./scripts/race_test.sh [number_of_users]      (default: 10)
#
# What it does, in order:
#   1. creates N throwaway users
#   2. temporarily "parks" every other free slot, then adds ONE fresh free slot
#   3. logs all users in, then fires N booking requests at the same instant
#   4. checks the database: exactly one booking must exist for that slot
#   5. cleans up everything it created and un-parks your slots

N="${1:-10}"
API="http://localhost:8080"
PG_CONTAINER="${PG_CONTAINER:-reservation-postgres-1}"
REDIS_CONTAINER="${REDIS_CONTAINER:-reservation-redis-1}"
HASH='$2b$10$8fgULuumWGfylM4TY5ZO5ez8NfvGEfvBWr7BhWnljJFO5v2Zg.FpK'   # bcrypt hash of "password123"
RUN="$(date +%s)"
TMP="$(mktemp -d)"
SLOT_ID=""
PARKED=""

psql_run() { docker exec -i "$PG_CONTAINER" psql -q -t -A -U azki -d bookingdb; }

cleanup() {
  echo ""
  echo "Cleaning up test data..."
  {
    [ -n "$SLOT_ID" ] && echo "DELETE FROM booking WHERE slot_id = $SLOT_ID;"
    echo "DELETE FROM booking WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'stress${RUN}_%');"
    [ -n "$SLOT_ID" ] && echo "DELETE FROM slot WHERE id = $SLOT_ID;"
    echo "DELETE FROM users WHERE email LIKE 'stress${RUN}_%';"
    [ -n "$PARKED" ] && echo "UPDATE slot SET is_reserved = false WHERE id IN ($PARKED);"
  } | psql_run
  rm -rf "$TMP"
}
trap cleanup EXIT

# --- 0. is the app up? ---
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$API/swagger-ui/index.html")"
if [ "$CODE" != "200" ]; then
  echo "App is not answering on $API (got '$CODE'). Start it with: docker compose up -d"
  exit 1
fi

# --- 1. create N throwaway users ---
echo "Creating $N test users..."
for i in $(seq 1 "$N"); do
  echo "INSERT INTO users (user_name, email, password, created_by, created_date, version) VALUES ('stress${RUN}_$i', 'stress${RUN}_$i@example.com', '$HASH', 'SYSTEM', now(), 0);"
done | psql_run

# --- 2. exactly one free slot ---
PARKED="$(echo "UPDATE slot SET is_reserved = true WHERE is_reserved = false AND start_time >= now() RETURNING id;" | psql_run | paste -sd, -)"
SLOT_ID="$(echo "INSERT INTO slot (start_time, end_time, is_reserved, created_by, created_date, version) VALUES (now() + interval '1 day', now() + interval '1 day 1 hour', false, 'SYSTEM', now(), 0) RETURNING id;" | psql_run | head -1 | tr -d '[:space:]')"
case "$SLOT_ID" in
  ''|*[!0-9]*) echo "Could not create the test slot (got '$SLOT_ID'). Is the database container name right?"; SLOT_ID=""; exit 1 ;;
esac
docker exec "$REDIS_CONTAINER" redis-cli FLUSHALL > /dev/null

# --- 3. log everyone in ---
echo "Logging in $N users..."
for i in $(seq 1 "$N"); do
  curl -s -X POST "$API/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"stress${RUN}_$i@example.com\",\"password\":\"password123\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" > "$TMP/token_$i" 2>/dev/null
  [ -s "$TMP/token_$i" ] || { echo "Login failed for user $i"; exit 1; }
done

# --- 4. the race ---
echo "Firing $N simultaneous booking requests at ONE free slot (id $SLOT_ID)..."
for i in $(seq 1 "$N"); do
  (
    curl -s -o "$TMP/body_$i" -w "%{http_code}" -X POST "$API/api/v1/bookings/reserve" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $(cat "$TMP/token_$i")" \
      -d "{\"email\":\"stress${RUN}_$i@example.com\"}" > "$TMP/code_$i"
  ) &
done
wait

echo ""
echo "HTTP responses (count, code):"
for f in "$TMP"/code_*; do cat "$f"; echo; done | sort | uniq -c | sed 's/^/  /'
echo "  (200 = booked directly, 202 = queued under load, 404 = no slot left, 503 = slot busy, retry)"

echo ""
echo "Waiting 5s for queued requests to finish..."
sleep 5

# --- 5. the verdict comes from the database, not from the HTTP codes ---
BOOKED="$(echo "SELECT count(*) FROM booking WHERE slot_id = $SLOT_ID;" | psql_run | tr -d '[:space:]')"
echo "Bookings in the database for slot $SLOT_ID: $BOOKED"
echo ""
if [ "$BOOKED" = "1" ]; then
  echo "PASS: $N users raced for 1 slot, exactly 1 booking exists."
else
  echo "FAIL: expected exactly 1 booking for the slot, found '$BOOKED'."
  exit 1
fi

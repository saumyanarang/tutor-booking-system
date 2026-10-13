#!/bin/bash
# Concurrency stress test against a RUNNING instance of the API (docker compose up -d --build).
#
# N students try to book the SAME single slot at the same moment.
# PASS = exactly one booking exists for that slot afterwards (nobody was double-booked).
#
# Uses only the public HTTP API - no SQL, no docker exec:
#   1. signs up one tutor and N students
#   2. the tutor publishes one slot
#   3. all N students POST /api/v1/bookings for that slot at the same instant
#   4. the verdict comes from the tutor's own "my bookings" list, not from the HTTP codes
#
# Usage:  ./scripts/race_test.sh [number_of_students]      (default: 10)
# Needs:  curl, python3.   API=http://host:port to point it elsewhere.
#
# The users it creates are left in the database (there is no delete-account endpoint); each run uses
# fresh unique emails, so re-running is always safe.
# The CI-run equivalent is src/test/java/.../integration/BookingConcurrencyTest.java (Testcontainers).

N="${1:-10}"
API="${API:-http://localhost:8080}"
RUN="$(date +%s)$RANDOM"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

register() {  # register <email> <username> <ROLE>  -> prints the JWT
  curl -s -X POST "$API/api/auth/register" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"userName\":\"$2\",\"password\":\"supersecret1\",\"role\":\"$3\"}" | json_field token
}

# --- 0. is the app up? ---
CODE="$(curl -s -o /dev/null -w '%{http_code}' "$API/swagger-ui/index.html")"
if [ "$CODE" != "200" ]; then
  echo "App is not answering on $API (got '$CODE'). Start it with: docker compose up -d --build"
  exit 1
fi

# --- 1. accounts ---
echo "Signing up 1 tutor and $N students..."
TUTOR_TOKEN="$(register "race-tutor-$RUN@example.com" "race_tutor_$RUN" TUTOR)" || true
[ -n "$TUTOR_TOKEN" ] || { echo "Tutor sign-up failed"; exit 1; }
for i in $(seq 1 "$N"); do
  register "race-student-$RUN-$i@example.com" "race_student_${RUN}_$i" STUDENT > "$TMP/token_$i" 2>/dev/null
  [ -s "$TMP/token_$i" ] || { echo "Sign-up failed for student $i"; exit 1; }
done

# --- 2. one slot, a week from now ---
START="$(python3 -c "import datetime as d; print((d.datetime.now()+d.timedelta(days=7)).replace(minute=0,second=0,microsecond=0).isoformat(timespec='seconds'))")"
END="$(python3 -c "import datetime as d,sys; print((d.datetime.fromisoformat('$START')+d.timedelta(hours=1)).isoformat(timespec='seconds'))")"
SLOT_ID="$(curl -s -X POST "$API/api/v1/slots" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TUTOR_TOKEN" -d "{\"startTime\":\"$START\",\"endTime\":\"$END\"}" | json_field id)"
case "$SLOT_ID" in
  ''|*[!0-9]*) echo "Could not create the slot (got '$SLOT_ID')."; exit 1 ;;
esac

# --- 3. the race ---
echo "Firing $N simultaneous booking requests at ONE slot (id $SLOT_ID)..."
for i in $(seq 1 "$N"); do
  (
    curl -s -o /dev/null -w "%{http_code}" -X POST "$API/api/v1/bookings" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $(cat "$TMP/token_$i")" \
      -d "{\"slotId\":$SLOT_ID}" > "$TMP/code_$i"
  ) &
done
wait

echo ""
echo "HTTP responses (count, code):"
for f in "$TMP"/code_*; do cat "$f"; echo; done | sort | uniq -c | sed 's/^/  /'
echo "  (201 = got the slot, 409 = slot already taken)"

# --- 4. the verdict: how many bookings does the tutor see on that slot? ---
BOOKED="$(curl -s "$API/api/v1/bookings/mine" -H "Authorization: Bearer $TUTOR_TOKEN" \
  | python3 -c "import sys,json; print(sum(1 for b in json.load(sys.stdin) if b['slotId']==$SLOT_ID))")"
echo ""
echo "Bookings on slot $SLOT_ID according to the tutor: $BOOKED"
echo ""
if [ "$BOOKED" = "1" ]; then
  echo "PASS: $N students raced for 1 slot, exactly 1 booking exists."
else
  echo "FAIL: expected exactly 1 booking for the slot, found '$BOOKED'."
  exit 1
fi

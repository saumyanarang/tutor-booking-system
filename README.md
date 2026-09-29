# Tutor Booking System — conflict-free slot booking

A Spring Boot backend for booking coaching/tuition slots without double-booking, even when many
people try to grab the same slot at the same moment.

Local tutors often manage bookings over WhatsApp and end up double-booking a slot. This project
focuses on the hard part of that problem: **two requests racing for the same slot must never both win.**

## Credit and what I changed

This project started from [HoomanDevp/reservation](https://github.com/HoomanDevp/reservation)
(an appointment-reservation backend; see that repository for its license terms). I used it as a
reference, got it running, and then reworked it:

- **Domain rename:** Reservation → Booking, AvailableSlot → Slot, new package `com.saumyanarang.tutorbooking`.
- **New locking design:** the original relied on a database row lock (`SELECT ... FOR UPDATE`) inside one
  big transaction. I put a **Redis distributed lock in front of the transaction** and split the database
  work into its own service (see below).
- **New tests:** 10 unit tests for the booking/lock logic, plus a concurrency stress script.
- **Bug fixes** in the original that stopped it from running (listed at the bottom).

## Tech stack

Java 21 · Spring Boot 3 · PostgreSQL 15 · Redis 7 · Liquibase · JWT auth · Docker Compose · JUnit 5 + Mockito

## How double-booking is prevented

Three layers, each catching what the previous one might miss:

1. **Redis lock per slot** — `SET booking:lock:slot:{id} <token> NX PX 5000`. Only one request at a time
   can work on a given slot. If the lock is taken, the request skips to the next free slot instead of waiting.
2. **Re-check inside the transaction** — after getting the lock, the slot is re-read and must still be free.
3. **`UNIQUE(slot_id)` on the `booking` table** — the final backstop. Even if the lock failed for some reason,
   PostgreSQL itself refuses a second booking for the same slot.

```
request -> pick nearest free slots -> tryLock(slot) -> [ BEGIN -> re-check -> insert booking -> COMMIT ] -> unlock
                                          |
                                     lock taken? -> try the next slot / tell the caller to retry (503)
```

Two details that matter:

- **The lock wraps the transaction, never the other way round.** The lock is released only *after* the commit.
  If it were released before, another request could read the slot before the change was visible and book it too.
  That is why the database work lives in a separate bean (`BookingTransactionService`): Spring only commits when
  that method returns through its proxy, which is before `BookingService` releases the lock.
- **Safe unlock.** Release is a small Lua script that deletes the key only if it still holds *this* request's
  token, so a request that outlived its 5-second lock can never delete somebody else's lock.

Under heavy load (5 or more simultaneous requests) the API queues extra requests in Redis and answers
`202 Accepted`; a worker processes them through the same locked booking flow.

## Proof it works

- **Unit tests:** `BookingServiceTest` (10 tests) covers: normal booking, duplicate booking by the same user,
  no free slots, skipping a locked slot, every slot locked, slot taken before the transaction ran,
  the database constraint rejecting a booking, and the lock being released even on unexpected errors.
- **Concurrency stress test:** `scripts/race_test.sh N` creates N throwaway users, leaves exactly one free
  slot, fires N booking requests at the same instant, and checks the database: **exactly one booking must
  exist.** It cleans up after itself.

```
./scripts/race_test.sh 10
# ...
# PASS: 10 users raced for 1 slot, exactly 1 booking exists.
```

## Quick start

Requirements: Docker Desktop.

```
git clone https://github.com/saumyanarang/tutor-booking-system.git
cd tutor-booking-system
docker compose up -d --build
```

Wait ~25 seconds, then open Swagger UI at <http://localhost:8080/swagger-ui/index.html>.

Container names come from the folder name. Run `docker ps` to see them; the commands below assume
`reservation-postgres-1`, so swap in your folder's name if it differs.

### Add demo data

There is no sign-up endpoint yet, so create a user and some future slots directly in the database
(the password for the demo user is `password123`):

```
docker exec -i reservation-postgres-1 psql -U azki -d bookingdb << 'SQL'
INSERT INTO users (user_name, email, password, created_by, created_date, version)
VALUES ('demo', 'demo@example.com', '$2b$10$8fgULuumWGfylM4TY5ZO5ez8NfvGEfvBWr7BhWnljJFO5v2Zg.FpK', 'SYSTEM', now(), 0);

INSERT INTO slot (start_time, end_time, is_reserved, created_by, created_date, version) VALUES
 (now() + interval '1 day',  now() + interval '1 day 1 hour',  false, 'SYSTEM', now(), 0),
 (now() + interval '2 days', now() + interval '2 days 1 hour', false, 'SYSTEM', now(), 0),
 (now() + interval '3 days', now() + interval '3 days 1 hour', false, 'SYSTEM', now(), 0);
SQL
```

### Log in and book a slot

```
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"password123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

curl -X POST http://localhost:8080/api/v1/bookings/reserve \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"email":"demo@example.com"}'
# {"requestId":"direct-1","status":"SUCCESS"}
```

Booking again with the same user returns `409` (a user can hold one active booking).

## API

| Method | Path | What it does |
|--------|------|--------------|
| POST | `/api/auth/login` | Log in, returns a JWT |
| POST | `/api/v1/bookings/reserve` | Book the nearest free slot for the given email |
| GET | `/api/v1/bookings/status/{requestId}` | Status of a queued booking request |
| DELETE | `/api/v1/bookings/cancel/{id}` | Cancel a booking and free its slot |

Send the token as `Authorization: Bearer <token>`. Response codes worth knowing:
`200` booked, `202` queued under load, `404` no free slot, `409` user already has a booking,
`503` slots are being booked right now, retry.

## Running the tests

```
docker run --rm -v "$PWD":/app -v "$HOME/.m2":/root/.m2 -w /app eclipse-temurin:21-jdk \
  ./mvnw test -Dtest=BookingServiceTest
```

## Bugs I found and fixed in the original project

- **Demo users could not log in.** The seeded passwords were placeholder text, not valid BCrypt hashes.
- **JWT signing key was one byte too short.** The hardcoded secret was 31 bytes (248 bits) but HS256 requires
  at least 256 bits, so every login crashed with a 500.
- **Redis connection silently used `localhost`.** `docker-compose.yml` set `SPRING_REDIS_*` variables, but
  Spring Boot 3 reads `spring.data.redis.*`, so the app could not reach Redis inside Docker.
- **Demo slots were in the past**, so no slot ever counted as "available". (Fixed by seeding relative dates above.)
- **`@Retryable` did nothing.** Retry was never enabled (`@EnableRetry` is missing), so the annotated retry
  logic never ran. The lock design no longer depends on it.

## Known limitations / next steps

- **13 inherited tests still fail** and are not part of this project's checks: the queue-service, controller,
  and application/repository tests are stale, and the repository/context tests need a Postgres database
  that only exists on the original author's machine. Next step: rewrite them with Testcontainers.
- The JWT secret is hardcoded in `JwtUtil`; it should come from an environment variable.
- No sign-up endpoint; users are created by SQL for now.
- `docker ps` shows the app as `unhealthy`: the compose healthcheck calls port 8080, but the Actuator
  health endpoint runs on 8081. Cosmetic; the API works.
- The booking endpoint picks the nearest slot automatically; a "book this specific slot" variant would be a
  natural next feature for real tutors.

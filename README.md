# Tutor Booking System

A tutor-and-student booking API built with Spring Boot 3 and Java 21 — tutors publish their own time slots, students book a **specific** slot, and a slot can never be double-booked, even when many students grab it at the same instant.

This is a backend-only, API-first portfolio project. There's no frontend; Swagger UI is the only interactive surface. The interesting part is the concurrency problem underneath: local tutors often manage bookings over WhatsApp and end up double-booking a slot, so the core of this project is making sure **two requests racing for the same slot can never both win** — and proving it with a test that runs ten simultaneous students against a real PostgreSQL and a real Redis.

## Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 3.5 (Web, Data JPA, Security, Validation, Cache, Actuator) |
| Database | PostgreSQL 15, schema managed by Liquibase (Hibernate only *validates* it) |
| Locking / cache | Redis 7 — per-slot distributed lock, Spring Cache |
| Auth | Stateless JWT (HS256), Spring Security, `TUTOR` / `STUDENT` roles |
| API docs | springdoc-openapi / Swagger UI |
| Testing | JUnit 5, Mockito, **Testcontainers** (real Postgres and real Redis, not H2 or mocks) |
| Containerization | Docker (multi-stage build), Docker Compose |
| CI | GitHub Actions — builds and runs the full test suite on every push and pull request |

## Architecture

A layered monolith, deliberately — no microservices, no premature distribution:

```
Controller → Service → Repository (Spring Data JPA) → PostgreSQL
                  ↘ Redis (per-slot lock, cache)
```

Cross-cutting concerns go through Spring's standard extension points rather than ad hoc code in controllers:

- **Security** — `JwtFilter` turns a bearer token into an authenticated user *with their role* (read from the database on every request, so it is never stale). `SecurityConfig` enforces the role rules; ownership rules (who may cancel a booking) are enforced in the service layer.
- **Errors** — every exception funnels through one `@RestControllerAdvice` (`GlobalExceptionHandler`) into a consistent JSON error shape with the right status code (400 / 401 / 403 / 404 / 409).
- **Schema** — Liquibase migrations create the tables, constraints and indexes; `ddl-auto: validate` makes a mismatch between an entity and the schema fail at startup instead of in production.

```
src/main/java/com/saumyanarang/tutorbooking/
├── controller/    REST controllers + GlobalExceptionHandler
├── service/       booking, slot, registration, Redis lock, queue
├── repository/    Spring Data JPA repositories
├── entity/        JPA entities + Role enum
├── dto/           request/response objects (auth, slot, booking)
├── config/        security, Redis, OpenAPI, JPA auditing
├── security/      JWT filter + token utility
├── exception/     custom exceptions
└── aspect/        logging / audit aspects
```

**Data model:** `User` (role: `TUTOR` or `STUDENT`) 1—* `Slot` (owned by a tutor), and `Slot` 1—0..1 `Booking` (made by a student). `UNIQUE(slot_id)` on `booking` means a slot has at most one booking, enforced by the database itself.

## How double-booking is prevented

Three layers, each catching what the previous one might miss:

1. **Redis lock per slot** — `SET booking:lock:slot:{id} <token> NX PX 5000`. Only one request at a time can work on a given slot; a second one is told immediately that the slot is being booked (409) rather than queuing behind it.
2. **Re-check inside the transaction** — after getting the lock, the slot is re-read and must still be free. The slot row also carries a version number, so two transactions that both read it as free cannot both save it.
3. **`UNIQUE(slot_id)` on the `booking` table** — the final backstop. Even if the lock failed, PostgreSQL itself refuses a second booking for the same slot.

```
POST /bookings {slotId}
   → checks (student? slot exists? not started? not already booked? no overlap with my other lessons?)
   → tryLock(slot) ─ taken? → 409
   → [ BEGIN → re-check → insert booking → COMMIT ]
   → unlock
```

Two details that matter:

- **The lock wraps the transaction, never the other way round.** The lock is released only *after* the commit; if it were released first, another request could read the slot before the change was visible and book it too. That is why the database work lives in a separate bean (`BookingTransactionService`): Spring commits when that method returns through its proxy, which is before `BookingService` releases the lock.
- **Safe unlock.** Release is a small Lua script that deletes the key only if it still holds *this* request's token, so a request that outlived its 5-second lock can never delete somebody else's lock.

## Features

- **Sign-up and login** — `POST /api/auth/register` creates a tutor or student account (BCrypt-hashed password, email normalised to lower case) and returns a JWT straight away. No SQL needed to create users.
- **Tutors publish their own slots** — a slot must be in the future, at most 8 hours long, and must not overlap another of the same tutor's slots (back-to-back is fine).
- **Students book a specific slot** by id — not just "the nearest one". A student can't hold two overlapping lessons.
- **Browse** free, not-yet-started slots from all tutors or from one tutor (paginated, page size capped at 100).
- **My bookings** — a student sees the lessons they booked; a tutor sees who booked their slots.
- **Cancel** — allowed for the student who made the booking and for the tutor who owns the slot, nobody else. Cancelling frees the slot.
- **Nearest-slot booking** — the original "book whichever slot is soonest" endpoint is kept for students.

## API reference

Auth endpoints and Swagger are public; everything else requires `Authorization: Bearer <token>`.

| Method | Route | Who | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | anyone | Sign up as `TUTOR` or `STUDENT`, get a token |
| POST | `/api/auth/login` | anyone | Log in, get a token |
| POST | `/api/v1/slots` | tutor | Publish a slot `{startTime, endTime}` |
| GET | `/api/v1/slots?tutorId=&page=&size=` | any user | Browse free slots, soonest first |
| POST | `/api/v1/bookings` | student | Book a specific slot `{slotId}` |
| GET | `/api/v1/bookings/mine` | any user | Your bookings (student) / bookings on your slots (tutor) |
| DELETE | `/api/v1/bookings/cancel/{id}` | booking's student or slot's tutor | Cancel and free the slot |
| POST | `/api/v1/bookings/reserve` | student | Book the nearest free slot `{email}` (your own email) |
| GET | `/api/v1/bookings/status/{requestId}` | any user | Status of a queued booking request |

Status codes you'll see: `201` created, `400` invalid input, `401` missing/invalid token, `403` wrong role or not yours, `404` no such slot/booking, `409` slot already taken (or being booked right now), email/username already registered, or overlapping lesson.

Times are local date-times without a time zone, e.g. `2027-01-15T16:00:00`.

## API documentation

Once running: <http://localhost:8080/swagger-ui/index.html>. Sign up or log in, click **Authorize**, paste the returned token (no `Bearer ` prefix), and every protected endpoint can be called from the browser. A ready-made [Postman collection](Tutor_Booking_System.postman_collection.json) walks through the whole journey.

## Running locally

Requires Docker and Docker Compose. No local Java, Maven, Postgres or Redis needed.

```bash
git clone https://github.com/saumyanarang/tutor-booking-system.git
cd tutor-booking-system
docker compose up -d --build
```

This builds the app image, starts Postgres and Redis, waits for both to report healthy, then starts the app. Once up: API at <http://localhost:8080>, Swagger UI at <http://localhost:8080/swagger-ui/index.html>, health at <http://localhost:8081/actuator/health>.

Try the whole flow with curl:

```bash
# A tutor and a student sign up (each response contains a token)
curl -s -X POST localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"email":"tutor@example.com","userName":"maths_mentor","password":"supersecret1","role":"TUTOR"}'
curl -s -X POST localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","userName":"alice","password":"supersecret1","role":"STUDENT"}'

# The tutor publishes a slot (use the tutor's token)
curl -s -X POST localhost:8080/api/v1/slots -H "Content-Type: application/json" \
  -H "Authorization: Bearer <tutorToken>" \
  -d '{"startTime":"2027-01-15T16:00:00","endTime":"2027-01-15T17:00:00"}'

# The student browses and books that specific slot (use the student's token and the slot's id)
curl -s localhost:8080/api/v1/slots -H "Authorization: Bearer <studentToken>"
curl -s -X POST localhost:8080/api/v1/bookings -H "Content-Type: application/json" \
  -H "Authorization: Bearer <studentToken>" -d '{"slotId":1}'
```

Stop everything with `docker compose down` (add `-v` to also wipe the database volume).

Set `JWT_SECRET` (32+ characters) in your environment or a `.env` file for anything beyond local use; a development default is built in.

### Running without Docker for the app

Start only the dependencies with `docker compose up -d postgres redis`, then run the app directly:

```bash
./mvnw spring-boot:run
```

## Testing

```bash
./mvnw test
```

Needs a running Docker daemon — the tests start their own disposable PostgreSQL and Redis with Testcontainers, so there is nothing to install or configure, and the tests run against the same engines production uses.

| Test | What it covers |
|---|---|
| `BookingConcurrencyTest` | **Ten students fire a booking for the same slot at the same instant** against real Postgres + Redis: exactly one gets `201`, nine get `409`, exactly one booking row exists, and the Redis lock is released. A second test bypasses Redis entirely and shows PostgreSQL alone still refuses to double-book. |
| `TutorBookingFlowTest` | End-to-end through the real security chain: sign-up validation and duplicates, login, `401`/`403` role rules, slot rules, booking a specific slot, overlapping lessons, who may cancel what. |
| `BookingRepositoryTest`, `TimeSlotRepositoryTest` | Queries and database constraints (unique slot, overlap detection, end-after-start) against real Postgres. |
| `BookingServiceTest`, `SlotServiceTest`, `RegistrationServiceTest`, `BookingControllerTest`, `BookingQueueServiceTest` | Fast unit tests of the decisions each class makes, with mocked collaborators. |
| `BookingApplicationTests` | The whole app starts, every migration applies, and the entities match the schema. |

`scripts/race_test.sh N` runs the same race against an already-running instance using only the public API.

## CI

`.github/workflows/ci.yml` runs `./mvnw verify` on every push and pull request to `main`. GitHub's hosted runners already have Docker, so the Testcontainers tests need no extra setup.

## Known limitations

Called out deliberately, as scoped-out decisions rather than oversights:

- **The queued-booking path is inert.** Under heavy load `POST /bookings/reserve` answers `202` and puts the request in a Redis queue, but the worker that drains it needs `@EnableScheduling`, which isn't turned on. I left it off on purpose: the same switch would also start an inherited "expiry" job that deletes every booking older than 24 hours — wrong for lessons booked days ahead. The specific-slot endpoint (`POST /bookings`) doesn't use the queue.
- **No refresh tokens or revocation** — a token stays valid for its 24 hours.
- **No email verification** and no rate limiting on sign-up/login.
- **No time zones** — slot times are local date-times; there is no per-user zone handling.
- **Single node** — the Redis lock is the right tool for one Redis instance, not a multi-master setup.
- The first migration still contains a few inherited demo rows (past-dated slots, placeholder users); they are harmless and never match a real login.

## Acknowledgements

This project started from [HoomanDevp/reservation](https://github.com/HoomanDevp/reservation), an appointment-reservation backend released under its own "Free To Use License" (see that repository for the terms). I used it as a starting point and reworked it:

- **Domain:** Reservation → Booking, AvailableSlot → Slot, a new package, and a new data model with tutor and student roles and slot ownership.
- **Features I added:** sign-up, tutor-published slots, booking a specific slot, per-user booking lists, ownership-checked cancellation, overlap rules.
- **Locking redesign:** the original relied on a database row lock inside one big transaction; I put a Redis distributed lock in front of a separate transaction bean and added the unique-constraint backstop.
- **Tests:** replaced the inherited tests that needed a hand-installed database with Testcontainers-based ones, and added the ten-user concurrency test.
- **Bug fixes** in the original: invalid seeded password hashes, a JWT secret one byte too short for HS256, Redis settings that Spring Boot 3 ignored inside Docker, past-dated demo slots, `@Retryable` that was never enabled, and a Docker healthcheck pointing at the wrong port.

The first Liquibase changeset still carries its original author tag; that is intentional, since renaming it would make Liquibase treat it as a new migration.

## License

Portfolio project. Because it builds on the project above, check that repository's license terms before reusing it commercially.

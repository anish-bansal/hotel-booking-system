# Hotel Booking Service

Backend for a hotel booking platform: owners onboard properties, guests search for what is
genuinely available, book it, pay for it, and cancel it.

Java 17 · Spring Boot 3.4 · Spring Data JPA · Maven · H2 by default, Postgres and Redis optional

> **Scope.** This is a focused prototype built for a design discussion, not a production system.
> It goes deep on the parts that are actually hard — preventing double-booking under concurrency,
> keeping payment idempotent, and putting the extension seams where the requirements say change will
> come — and deliberately stops short elsewhere. [What I would do with more time](#what-i-would-do-with-more-time)
> is explicit about where the line is.

---

## Build and run

**Requires a JDK 17+ and nothing else.** Maven comes from the wrapper. Docker only if you want real
Postgres or Redis.

### Build and test

```bash
./mvnw clean test
```

```bash
./mvnw spring-boot:run
```

The service starts on `http://localhost:8080` with a few properties already seeded, so the API is
explorable immediately. Interactive API docs:

| | |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| What is wired | http://localhost:8080/api/v1/system/capabilities |

Full endpoint reference with runnable `curl` for every call: **[API.md](API.md)**. A Postman
collection that chains its own ids and asserts on each response:
**[`postman_collection.json`](postman_collection.json)** — import it, hit Run, 22 requests pass in
order. It doubles as a headless test suite via `npx newman run postman_collection.json`.

### One command, end to end

`./run.sh` additionally checks your toolchain, starts the server, waits for a real readiness signal,
and drives the whole search → book → pay → cancel flow through the live HTTP API with a pass/fail
per step.

```bash
./run.sh
```

Missing a dependency? `./run.sh --doctor` reports what you have and what you need;
`./run.sh --install-deps` installs it via Homebrew and carries on; `./run.sh --dry-run` prints the
exact `brew` commands without running them. Full detail in **[SETUP.md](SETUP.md)** — including the
macOS trap where `brew install openjdk@21` succeeds and `java -version` *still* reports 11, because
Homebrew's JDK is keg-only.

A successful run looks like this:

```
==> Preflight
    ✓ Java 21.0.10
    ✓ Maven wrapper (./mvnw)
    ✓ Port 8080 free

==> External service connectivity
    ✓ Database reachable — PostgreSQL
    ✓ Redis reachable — v7.4.2

==> Wiring discovered at startup
    ✓ 3 payment gateways registered
    ✓ 3 cancellation policies registered
    ✓ 3 search filters registered
    ✓ Sweeper using the cluster-safe Redis lock

==> End-to-end flow
    ✓ Onboard multi-property owner -> 201
    ✓ Reported as a chain, not standalone
    ✓ Price filter excluded the Penthouse
    ✓ 3 nights x 6000 quoted correctly
    ✓ Booking starts unpaid
    ✓ Charged price matches the quoted price
    ✓ Unpaid hold already removed a room from sale
    ✓ Payment outcome confirmed the booking
    ✓ Retry with the same key -> 200, not 201
    ✓ Replay returned the original payment id
    ✓ Exactly one payment record exists
    ✓ Paying a confirmed booking again -> 409
    ✓ Full refund at 30 days' notice
    ✓ Cancelling twice -> 409
    ✓ Released room is bookable again

==> Result
    ALL CHECKS PASSED — 42/42
```

*(Abridged — the flow section prints roughly twice this many lines. The exact total varies with the
flags: 42 on plain `./run.sh`, more with `--postgres`/`--redis`, since there is then something to
assert about them.)*

The distinction the script is built around: **a started process is not a working service.** So it
never reports success on the strength of a PID. Readiness comes from `/actuator/health` actually
returning `UP` — which means the connection pool got a connection — and correctness comes from
exercising the real endpoints.

| Command | What it does |
|---|---|
| `./run.sh` | H2 in memory. No Docker, no setup. |
| `./run.sh --postgres` | Real Postgres in Docker. Proves the persistence swap. |
| `./run.sh --redis` | Real Redis in Docker. Swaps in the cluster-safe sweeper lock. |
| `./run.sh --all` | Both. |
| `./run.sh --test` | Run the full suite first, including the 20-thread concurrency test. |
| `./run.sh --smoke` | Verify, then shut down and exit non-zero on failure. For CI. |
| `./run.sh --stop` | Stop the server and its containers. |
| `./run.sh --port 9090` | Different port. |
| `./run.sh --doctor` | Report every dependency, change nothing, exit. |
| `./run.sh --install-deps` | Install what is missing via Homebrew, then run. |
| `./run.sh --dry-run` | Print the install commands without running them. |
| `./run.sh --help` | All options. |

Written for macOS and deliberately limited to bash 3.2 features, since that is still what `/bin/bash`
is there.

### Or by hand

```bash
./mvnw clean verify        # compile + run the full test suite
./mvnw spring-boot:run     # start on http://localhost:8080

SPRING_PROFILES_ACTIVE=postgres,redis ./mvnw spring-boot:run   # against real services
```

With no profile, H2 runs in memory, the schema is created at startup, and demo data is seeded (a
two-property chain, a standalone hotel, all three cancellation policies) so the API is explorable
immediately.

- H2 console: <http://localhost:8080/h2-console> — JDBC URL `jdbc:h2:mem:hotelbooking`, user `sa`, no password
- Health: <http://localhost:8080/actuator/health> — per-dependency status
- Wiring: <http://localhost:8080/api/v1/system/capabilities> — what got discovered at startup

The Maven wrapper is committed, so a JDK is the only prerequisite — `./mvnw` fetches the exact Maven
this project was built against rather than relying on whatever version happens to be installed.

---

## External services

Both are **optional and off by default** — the service runs with zero infrastructure. Each is behind
a Spring profile, and switching one on changes configuration only. No repository, service, entity or
query differs between profiles, which is the point: if swapping the database had required touching
`domain` or `application`, the repository ports would have been decoration.

| Service | Profile | What it is used for |
|---|---|---|
| **Postgres 16** | `postgres` | Primary datastore instead of H2 |
| **Redis 7** | `redis` | A cluster-safe lock so the hold-expiry sweeper runs once, not once per instance |

```bash
docker compose up -d postgres redis     # or just let ./run.sh --all do it
```

### Creating the schema explicitly

By default the `postgres` profile runs `ddl-auto: update` and lets Hibernate build the tables, which
is the zero-setup path. `db/` holds the same schema written out by hand, for when you would rather
see it — or own it — than infer it:

```bash
./db/setup.sh          # creates the role, the database, and every table
```

Works against any reachable PostgreSQL (local install or Docker), and is idempotent — re-running it
is also how you reset to a clean slate. Override with `PGDATABASE`, `PGHOST`, `DB_USER`,
`DB_PASSWORD`.

| File | What it is |
|---|---|
| [`db/schema.sql`](db/schema.sql) | 7 tables, with named constraints, query-driven indexes, and CHECK constraints for the domain invariants |
| [`db/setup.sh`](db/setup.sh) | Role + database + schema in one command |

The hand-written DDL is not a guess at what the entities need — it is verified against them:

```bash
SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments=-Dspring.jpa.hibernate.ddl-auto=validate
```

`validate` fails startup on any mismatch in table, column, type or nullability, so a clean boot is
proof the two agree.

**What the DDL adds over `ddl-auto`.** Hibernate creates no indexes beyond those implied by primary
and unique keys, and PostgreSQL — unlike MySQL — does not index foreign-key columns automatically. So
`db/schema.sql` adds an index per real access path: `(city, locality)` for search,
`(guest_email, created_at DESC)` for a guest's bookings, `(booking_id, status, created_at DESC)` for
payment lookups, plus a *partial* index on `hold_expires_at WHERE status = 'PENDING_PAYMENT'` — the
sweeper only ever reads unpaid holds, so confirmed and cancelled bookings stay out of the index
entirely.

It also states the domain invariants as constraints, so they hold against a stray `UPDATE` and not
only against code that goes through the aggregates — `held_rooms <= total_rooms` being the one the
whole design exists to protect.

**Postgres also settles a question H2 could only answer approximately.** The whole double-booking
defence rests on `SELECT … ORDER BY … FOR UPDATE`; Postgres supports that unambiguously. Running the
concurrency test under this profile is the real proof:

```bash
docker compose up -d postgres          # or a local install; see SETUP.md
createdb -O hotelbooking hotelbooking_test
./mvnw test -Ppostgres-it -Dtest=ConcurrentBookingIntegrationTest
```

Note the Maven profile rather than `SPRING_PROFILES_ACTIVE=postgres`. The integration tests declare
`@ActiveProfiles("test")`, and that annotation outranks the environment variable — so exporting the
profile leaves the tests quietly running on H2 while looking like it worked. `-Ppostgres-it` sets the
datasource directly, and uses a separate `hotelbooking_test` database so a test run cannot drop the
schema you were demoing against.

**Redis closes a gap this README previously just admitted to.** With more than one instance, every
copy of the sweeper fired on its own timer — survivable, since each booking's state machine rejects a
double expiry, but N instances doing N times the work and contending on the same inventory rows to
discover there was nothing to do. `SweepLock` now has two implementations and
`HoldExpirySweeper` takes whichever bean exists:

- `InProcessSweepLock` — default, no infrastructure, correct for one instance
- `RedisSweepLock` — `SET key token NX PX ttl`, correct for a cluster

No `if` in the sweeper, and no business code learns that Redis exists. What it is *not* used for is
caching search results: inventory changes on every booking, so a cached availability answer is a
wrong availability answer, and the invalidation needed to make it safe would be more delicate than
the query it replaced.

---

## The five-minute walkthrough

Each step below is a real request against a freshly started service. `jq` is only for readability.

### 1. Onboard an owner

A standalone hotel and a fifty-property chain use the *same* endpoint — a standalone owner is just a
group whose property list has one element. Here is a two-property chain:

```bash
curl -s -X POST http://localhost:8080/api/v1/property-groups \
  -H 'Content-Type: application/json' \
  -d '{
    "groupName": "Harbour Hotels",
    "contactEmail": "owner@harbourhotels.example",
    "properties": [
      {
        "name": "Harbour Grand",
        "city": "Bengaluru",
        "locality": "Indiranagar",
        "addressLine": "80 Ft Road",
        "starRating": 5,
        "amenities": ["WIFI", "POOL", "GYM", "SPA"],
        "cancellationPolicyCode": "MODERATE",
        "roomTypes": [
          { "name": "Deluxe King",  "maxOccupancy": 2, "totalRooms": 6, "basePricePerNight": 7000.00 },
          { "name": "Penthouse",    "maxOccupancy": 4, "totalRooms": 1, "basePricePerNight": 30000.00 }
        ]
      },
      {
        "name": "Harbour Beach",
        "city": "Goa",
        "locality": "Candolim",
        "addressLine": "Beach Road",
        "starRating": 4,
        "amenities": ["WIFI", "POOL", "BAR", "PET_FRIENDLY"],
        "cancellationPolicyCode": "FLEXIBLE",
        "roomTypes": [
          { "name": "Sea View Twin", "maxOccupancy": 2, "totalRooms": 10, "basePricePerNight": 4500.00 }
        ]
      }
    ]
  }' | jq
```

The response reports `"standalone": false` and `"propertyCount": 2`. Note that `standalone` is
*derived*, never stored — see [Design decision 1](DESIGN.md#1-a-single-property-is-a-group-of-one).
Onboarding also opens nightly inventory for every room type, so these properties are bookable
immediately.

**Grow a chain** (or turn a standalone owner into one — same call, no conversion step):

```bash
curl -s -X POST http://localhost:8080/api/v1/property-groups/{groupId}/properties \
  -H 'Content-Type: application/json' -d '{ ...one property... }' | jq
```

### 2. Search

Only properties genuinely available for the whole stay come back, priced for the whole stay.

```bash
curl -s -X POST http://localhost:8080/api/v1/properties/search \
  -H 'Content-Type: application/json' \
  -d '{
    "city": "Bengaluru",
    "locality": "Indiranagar",
    "checkIn": "2026-09-10",
    "checkOut": "2026-09-13",
    "guests": 2,
    "maxNightlyPrice": 10000.00,
    "amenities": ["WIFI", "POOL"],
    "minStarRating": 4
  }' | jq
```

Each result carries its available room types with `roomsAvailable`, the nightly rate, and
`totalForStay`. The total comes from the same `PricingStrategy` the booking path uses, so the search
price and the checkout price cannot drift apart.

### 3. Book

Holds the rooms and returns a `PENDING_PAYMENT` booking with a hold deadline.

```bash
curl -s -X POST http://localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{
    "propertyId": "{propertyId}",
    "roomTypeId": "{roomTypeId}",
    "guestName": "Asha Menon",
    "guestEmail": "asha@example.com",
    "checkIn": "2026-09-10",
    "checkOut": "2026-09-13",
    "guests": 2,
    "rooms": 1
  }' | jq
```

Re-run the search now: `roomsAvailable` has dropped. An unpaid hold takes the room off sale
immediately — that is the point of holding before charging.

The response includes `allowedNextStates`, read straight from the domain's transition table, so a
client never has to hard-code the lifecycle.

### 4. Pay

`Idempotency-Key` is a **required** header.

```bash
curl -s -X POST http://localhost:8080/api/v1/bookings/{bookingId}/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: checkout-7f3a91' \
  -d '{ "method": "CARD", "payerReference": "asha-visa" }' | jq
```

`201` with `"status": "SUCCESSFUL"` and the booking now `CONFIRMED` — the payment outcome drives the
booking state, and `PaymentService` is the only caller of `confirm()` in the codebase.

**Send the exact same request again.** You get `200` (not `201`), the identical payment id and
gateway reference, and `"idempotentReplay": true`. The gateway is not called a second time and no
second charge exists. Try it with `POST /api/v1/bookings/{id}/payments` listed via `GET` on the same
path — there is exactly one payment record.

**To see a decline**, use an idempotency key starting with `DECLINE`:

```bash
curl -s -X POST http://localhost:8080/api/v1/bookings/{bookingId}/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: DECLINE-me-1' \
  -d '{ "method": "CARD" }' | jq
```

The payment is `FAILED`, and the booking stays `PENDING_PAYMENT` so the guest can retry with another
method while the hold lasts. `UPI` and `WALLET` work too — the wallet declines on insufficient
balance, which is a second, more realistic failure shape.

### 5. Cancel

```bash
curl -s -X POST http://localhost:8080/api/v1/bookings/{bookingId}/cancellation | jq
```

Returns the refund amount, the policy that produced it, the reason in words, and how many rooms went
back on sale. Search the same dates again — the property is available once more.

Cancel twice and you get `409 ILLEGAL_STATE_TRANSITION`. The booking entity rejects it, so no service
or controller can be talked into a double refund.

---

## API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/property-groups` | Onboard an owner with one or many properties |
| `GET` | `/api/v1/property-groups/{groupId}` | Owner and everything under it |
| `POST` | `/api/v1/property-groups/{groupId}/properties` | Add a property to an existing owner |
| `GET` | `/api/v1/property-groups/cancellation-policies` | Which policies this deployment has registered |
| `POST` | `/api/v1/properties/search` | Discovery — location, dates, party, price, amenities, rating |
| `POST` | `/api/v1/bookings` | Create a booking, holding inventory |
| `GET` | `/api/v1/bookings/{bookingId}` | One booking |
| `GET` | `/api/v1/bookings?guestEmail=…` | A guest's bookings |
| `POST` | `/api/v1/bookings/{bookingId}/payments` | Pay (requires `Idempotency-Key`) |
| `GET` | `/api/v1/bookings/{bookingId}/payments` | Payment attempt history |
| `POST` | `/api/v1/bookings/{bookingId}/cancellation` | Cancel, release rooms, settle refund |

### Error shape

One shape for every failure, with a stable machine-readable `code` so clients never parse messages:

```json
{
  "code": "INVENTORY_UNAVAILABLE",
  "message": "Only 0 room(s) available on 2026-09-11 but 1 requested",
  "timestamp": "2026-08-22T10:15:30Z",
  "details": { "firstUnavailableNight": "2026-09-11" }
}
```

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bad input — payload or domain rule |
| `NOT_FOUND` | 404 | Unknown booking / property / owner |
| `INVENTORY_UNAVAILABLE` | 409 | Rooms gone. Well-formed request that lost a race — retry different dates, not a different payload |
| `ILLEGAL_STATE_TRANSITION` | 409 | Cancelling twice, paying an expired booking, etc. |
| `DUPLICATE_REQUEST` | 409 | Concurrent duplicate idempotency key; retry to get the winner's result |
| `PAYMENT_FAILED` | 402 | Gateway declined |
| `INTERNAL_ERROR` | 500 | Unexpected. Deliberately does **not** echo the exception message — that is how an internal error becomes an information leak |

---

## Architecture

```
com.rupeek.hotelbooking
├── domain/                  the business, with no opinion about HTTP or Spring wiring
│   ├── model/               aggregates: PropertyGroup, Property, RoomType,
│   │                        RoomInventory, Booking, Payment  (+ status enums that
│   │                        own their transition tables)
│   ├── vo/                  Money, DateRange, Location, Amenity — immutable, self-validating
│   ├── policy/              PricingStrategy, CancellationPolicy family, registry
│   ├── search/              PropertySearchCriteria + the composable PropertyFilter chain
│   ├── port/                outbound contracts: *Repository, PaymentGateway
│   └── exception/           the domain's own failure vocabulary
├── application/             use-case services; orchestration and transaction boundaries
│   ├── command/             inputs
│   └── result/              read models
├── infrastructure/          the outside world
│   ├── persistence/         Spring Data adapters implementing the domain ports
│   ├── gateway/             mock payment gateways, one per method
│   └── config/              bean wiring, demo seed, hold-expiry sweeper
└── api/                     REST controllers, DTOs, one global exception handler
```

Dependencies point **inwards**. `domain` knows nothing of `application`, `application` knows nothing
of `infrastructure` or `api`, and no service or entity imports `org.springframework.http` or
`org.springframework.data`.

The one honest compromise: **entities carry JPA annotations.** A textbook hexagonal build would keep
`domain` framework-free and map to separate persistence entities in `infrastructure`. That doubles
the class count for a prototype, so instead the aggregates are rich objects that happen to be
`@Entity`, while the *repository ports* stay pure domain interfaces with `infrastructure` adapters
behind them. The dependency inversion that matters is intact; the annotation purity is not. This is
[trade-off 1](DESIGN.md#trade-offs-taken-knowingly), and I am happy to argue either side of it.

**[→ DESIGN.md](DESIGN.md)** has the domain model, the state machine, the concurrency mechanism, and
the reasoning behind each decision including the alternatives that were rejected.

---

## Where each requirement lives

| Requirement | Where | The interesting bit |
|---|---|---|
| Search by city/locality, dates, guests | `PropertySearchService` | Four-stage pipeline, cheapest filter first, availability last |
| Pluggable filters | `domain/search/PropertyFilter` | Service holds a `List<PropertyFilter>`; a new filter is one class + one bean, and this file does not change |
| Only genuinely available results | `InventoryService.lowestAvailabilityPerRoomType` | Aggregates the **minimum** across nights — a stay needs the room on every night |
| Onboard a property | `PropertyOnboardingService` | Also opens nightly inventory, so "room type with no inventory" is not a reachable state |
| Single **and** multi-property owner | `PropertyGroup` | One shape only; `isStandalone()` is derived, not stored |
| Prevent double-booking | `InventoryService.reserve` | Date-ordered `SELECT … FOR UPDATE`, check-all-then-mutate-all |
| Booking lifecycle | `Booking` + `BookingStatus` | Transition table in the enum; no status setter exists |
| Multiple payment methods | `PaymentGateway` + `PaymentGatewayRegistry` | Registry discovers gateways; no `switch` anywhere |
| Payment drives booking state | `PaymentService` | The only caller of `Booking.confirm()` in the codebase |
| Pluggable cancellation policy | `CancellationPolicy` + registry | Chosen per *property*, stored as a code — so a chain can mix policies |
| Release inventory on cancel | `CancellationService` | Unconditional, even for non-refundable rates |
| Mocked third party | `infrastructure/gateway/Mock*Gateway` | Deterministic, not random — see [Testing](#testing) |
| Swappable persistence | `domain/port/*Repository` + `application-postgres.yml` | H2 → Postgres changes one yml file and nothing else |
| Runs safely as a cluster | `infrastructure/lock/SweepLock` | Two impls, chosen by which bean exists; the sweeper has no `if` |

---

## Testing

```bash
./mvnw test
```

Fast unit tests need no Spring context at all — a `Money`, a `Booking`, a `CancellationPolicy` and a
`PropertyFilter` can each be exercised with `new`. That they *can* be is the evidence the seams are
in the right places; if testing a filter required booting a context, the abstraction would be wrong.

The tests worth reading:

| Test | What it pins down |
|---|---|
| **`ConcurrentBookingIntegrationTest`** | 20 threads race for 1 room: exactly one wins, and the room is held exactly once. A second case runs *staggered overlapping* stays, which is the arrangement that deadlocks if the lock ordering is wrong — that it terminates is the evidence for ascending-date locking. |
| **`PaymentIdempotencyIntegrationTest`** | No double charge, in both shapes: sequential retry (caught by the idempotency lookup) and 8 concurrent duplicates (caught by the unique constraint). Asserts against the **wallet balance**, not our own records — "did the money move twice?" should be answered by looking at the money. |
| `BookingLifecycleIntegrationTest` | search → book → pay → cancel end to end; sold-out properties vanish from search for those dates only; a partly-available stay is refused *atomically* with nothing half-held; a declined card leaves the hold intact so a retry works; an expired hold returns its rooms. |
| `CancellationPolicyTest` | Refund tiers asserted **on their boundaries** — 24 hours exactly vs one minute inside, 7 days exactly vs one minute under. Possible only because the clock is injected. |
| `BookingTest` / `BookingStatusTest` | Every illegal transition, attempted directly on a bare entity with no service in the way. |
| `DateRangeTest` | The half-open `[checkIn, checkOut)` convention, including that check-out day is not a night and back-to-back stays do not collide. |

Three deliberate choices in the concurrency tests, because each is a way such a test can silently
prove nothing:

1. **No `@Transactional` on the test class.** It would enrol every thread in one shared transaction,
   removing the contention the test exists to create.
2. **A `CountDownLatch` start gate.** Threads submitted to a pool start staggered enough that each
   booking can finish before the next begins — the race would never actually run.
3. **The final assertion reads the database.** Counting successes proves the API behaved; reading
   `heldRooms` back proves the inventory did.

Time is injected everywhere (`java.time.Clock`), and tests substitute a `MutableClock` they move by
hand. Hold expiry and the refund boundaries are assertions about elapsed time; with the system clock
they would be untestable, flaky, or both.

---

## Assumptions

1. **No authentication or authorisation.** Out of scope per the brief. Any caller can act as any
   guest or owner; there is no tenancy check on `groupId`. In production, owner endpoints would be
   scoped to the authenticated account and `POST /property-groups/{id}/properties` would verify
   ownership.
2. **Inventory is a count per (room type, night), not a named room.** How real OTAs model it, and
   what makes availability integer arithmetic rather than a matching problem. It means "give me room
   402" cannot be expressed.
3. **Single currency (INR).** `Money` carries and enforces its currency and refuses to mix, so
   multi-currency is a data change rather than a rewrite — but no FX conversion exists.
4. **Dates are UTC, and a night is a calendar date.** A real system needs the *property's* timezone:
   check-in on 10 March means something different in Goa than in Denver. `Location` is where that
   would live.
5. **Nightly inventory is materialised 365 days ahead at onboarding.** Bookings beyond the horizon
   are refused with a clear message rather than silently failing. Creating rows up front rather than
   lazily removes a second race (two transactions both discovering a night has no row and both
   inserting one), leaving the booking path with exactly one concurrency concern.
6. **Unpaid bookings hold rooms for 15 minutes**, then a sweeper returns them to sale. Configurable
   via `hotel-booking.booking.payment-hold-minutes`.
7. **The gateways are mocks, and deterministically so.** An idempotency key starting with `DECLINE`
   is declined by the card gateway; the wallet declines on insufficient funds. A randomly-failing
   mock would make the failure-path tests as flaky as the mock.
8. **A declined payment consumes its idempotency key.** Retrying with the same key replays the
   decline rather than charging afresh — the same request must yield the same outcome. A genuine new
   attempt uses a new key, which is what clients do per checkout anyway.
9. **Search is a `POST`.** The criteria set is open-ended by design; encoding a growing structured
   object into a query string ends in `?amenities=WIFI&amenities=POOL&…` and a URL length limit. The
   cost is that searches are not URL-cacheable, which is moot for a result that depends on live
   inventory.
10. **One instance.** The hold sweeper would run on every node of a multi-instance deployment. It is
    survivable — each booking's own transition guard rejects a double expiry — but a real deployment
    wants a distributed lock so the work happens once.
11. **Star rating and amenities are enums/ints supplied at onboarding**, with no verification workflow.

---

## Two things I could not verify in my environment

I built this without network access to Maven Central, so `mvn verify` did not run here. The code was
verified by compiling against hand-written stubs of the Spring/JPA/AssertJ APIs, which catches type
errors but not bootstrap or dialect behaviour. Two spots are worth a second look on first run, and
both have a one-line fallback:

1. **`SELECT … ORDER BY … FOR UPDATE` on H2.** `RoomInventoryJpaRepository.lockRangeForUpdate` relies
   on H2 accepting a pessimistic-write lock on an ordered query. I believe it does. **If it does not,
   the `postgres` profile is the answer rather than a workaround** — Postgres supports the
   combination unambiguously, so `./run.sh --postgres --test` both sidesteps the doubt and gives a
   stronger result than H2 agreeing would have. The other mechanisms considered are in
   [DESIGN.md](DESIGN.md#considered-and-rejected).
2. **`@EntityGraph` over already-eager associations** in `PropertyJpaRepository.findByLocationCity`.
   It is valid and redundant — the graph exists to make the fetch join explicit and avoid N+1. If it
   misbehaves, delete the annotation; the associations are `EAGER` regardless.

---

## What I would do with more time

Ordered by what I would reach for first.

1. **An outbox for the refund.** The one real correctness gap: `CancellationService` calls the
   gateway inside the database transaction. If the refund succeeds and the commit then fails, our
   records disagree with the provider's. The fix is to persist the refund *intent*, settle it
   asynchronously, and reconcile — the same pattern the charge path would need for at-most-once
   guarantees under partial failure. I left it out because a correct outbox is a bigger piece of
   machinery than the rest of this service, and pretending otherwise with a `try/catch` would be
   worse than naming the gap.
2. **Dynamic pricing.** `PricingStrategy` exists precisely so this is additive: a decorator chain of
   `WeekendSurcharge` → `LengthOfStayDiscount` → `OccupancyBasedYield` wrapping
   `StandardPricingStrategy`, each independently testable. The seam is built; only the implementations
   are missing.
3. **Optimistic locking as a second mode.** Pessimistic row locks are the right default for
   high-contention inventory, but under low contention an `@Version` + bounded-retry path performs
   better. Both behind `InventoryService` with a strategy switch would make the trade measurable
   rather than argued.
4. **Move the booking horizon off onboarding.** 365 rows per room type at onboarding is fine at this
   scale and wasteful at real scale. A rolling window job extending the horizon nightly, plus
   `INSERT … ON CONFLICT DO NOTHING` for lazy creation, removes the fixed cost.
5. **Amenities as reference data.** An enum gives type-safe filtering and costs a deploy to add
   "EV charging". A reference table with a cached lookup keeps the safety at the API boundary while
   letting operations add one.
6. **OpenAPI, pagination and sorting on search.** `springdoc-openapi-starter-webmvc-ui` is one
   dependency and one annotation pass; search returning an unbounded list is fine for a prototype and
   not for a city with 4,000 hotels.
7. **Property timezones**, so "check-in on the 10th" means the same thing to the guest and the hotel.
8. **Testcontainers**, so the Postgres profile is exercised by `mvn verify` in CI rather than by a
   developer remembering to pass `--postgres`. The profile and the compose file already exist; this is
   about making the suite start the container itself.
9. **Flyway** instead of `ddl-auto: update` on the Postgres profile. Fine for a prototype, not for
   anything that has to be migrated twice.

Two items that *were* on this list are now done, and are worth mentioning only because the README used
to promise them: the sweeper has a distributed lock (`RedisSweepLock`), and there is a real Postgres
profile rather than H2 alone.

---

## Configuration reference

| Property | Default | Purpose |
|---|---|---|
| `hotel-booking.inventory.booking-horizon-days` | `365` | How far ahead nightly inventory is created at onboarding |
| `hotel-booking.booking.payment-hold-minutes` | `15` | How long an unpaid booking holds its rooms |
| `hotel-booking.booking.hold-sweep-interval-ms` | `60000` | How often lapsed holds are released |
| `hotel-booking.demo-data.enabled` | `true` (H2) / `false` (Postgres) | Seed demo properties at startup |

Under the `postgres` profile, read from the environment with these defaults:

| Variable | Default |
|---|---|
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` |
| `POSTGRES_DB` | `hotelbooking` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `hotelbooking` / `hotelbooking` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |

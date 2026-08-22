# Hotel Booking Service

Backend for a hotel booking platform: owners onboard properties, guests search what is genuinely
available, book it, pay for it, and cancel it.

**Java 17 · Spring Boot 3.4 · Spring Data JPA · H2 by default, PostgreSQL and Redis optional**

`97 tests` · green on H2 and PostgreSQL

> **Scope.** A focused prototype built for a design discussion, not a production system. It goes deep
> on the parts that are actually hard — preventing double-booking under concurrency, keeping payment
> idempotent, and putting the extension seams where the requirements say change will come — and
> deliberately stops short elsewhere.
> [What I would do with more time](#what-i-would-do-with-more-time) says where the line is.

---

## Quick start

Requires a **JDK 17+ and nothing else** — Maven comes from the committed wrapper.

```bash
./mvnw spring-boot:run
```

Starts on `http://localhost:8080` with demo properties already seeded, so the API is explorable
immediately.

```bash
./mvnw test          # 97 unit + integration tests
./run.sh             # build, start, and drive the whole flow through the live API
```

`./run.sh` is the one that proves it works: it waits for a real readiness signal, then exercises
search → book → pay → cancel over HTTP with a pass/fail per step, ending in `ALL CHECKS PASSED —
42/42`.

| | |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| What is wired | http://localhost:8080/api/v1/system/capabilities |
| H2 console | http://localhost:8080/h2-console — `jdbc:h2:mem:hotelbooking`, user `sa`, no password |

## Documentation

| | |
|---|---|
| **[API.md](API.md)** | Every endpoint, with runnable `curl` and real captured responses |
| **[DESIGN.md](DESIGN.md)** | Domain model, state machine, concurrency mechanism, and the alternatives rejected |
| **[SETUP.md](SETUP.md)** | Toolchain troubleshooting, including the macOS keg-only JDK trap |
| **[`postman_collection.json`](postman_collection.json)** | 22 requests that chain their own ids — import and hit Run |

---

## What it does

| Requirement | Where | The interesting bit |
|---|---|---|
| Search by city/locality, dates, guests | `PropertySearchService` | Four-stage pipeline, cheapest filter first, availability last |
| Pluggable filters | `domain/search/PropertyFilter`, `RoomTypeFilter` | Two chains — one picks properties, one picks rooms within them. A new filter is one class plus one bean; the search service never changes |
| Only genuinely available results | `InventoryService.lowestAvailabilityPerRoomType` | Aggregates the **minimum** across nights — a stay needs the room every night |
| Onboard a property | `PropertyOnboardingService` | Also opens nightly inventory, so "room type with no inventory" is unreachable |
| Single **and** multi-property owner | `PropertyGroup` | One shape only; `isStandalone()` is derived, never stored |
| Prevent double-booking | `InventoryService.reserve` | Date-ordered `SELECT … FOR UPDATE`, check-all-then-mutate-all |
| Booking lifecycle | `Booking` + `BookingStatus` | Transition table lives in the enum; no status setter exists |
| Multiple payment methods | `PaymentGateway` + `PaymentGatewayRegistry` | Registry discovers gateways; no `switch` anywhere |
| Payment drives booking state | `PaymentService` | The only caller of `Booking.confirm()` in the codebase |
| Pluggable cancellation policy | `CancellationPolicy` + registry | Chosen per *property*, stored as a code — a chain can mix policies |
| Release inventory on cancel | `CancellationService` | Unconditional, even for non-refundable rates |
| Mocked third party | `infrastructure/gateway/Mock*Gateway` | Deterministic, not random |
| Swappable persistence | `domain/port/*Repository` | H2 → PostgreSQL changes one yml file and nothing else |
| Runs safely as a cluster | `infrastructure/lock/SweepLock` | Two impls chosen by which bean exists; the sweeper has no `if` |

All four bonus items are covered: concurrency handling, a pluggable pricing strategy, payment
idempotency, and OpenAPI.

---

## Design decisions worth defending

**Inventory is a count per (room type, night), not a named room.** Guests do not care which of eight
identical deluxe rooms they get. Availability becomes integer arithmetic over a date range instead of
a matching problem, which keeps the concurrency-critical section small. The cost: "give me room 402"
cannot be expressed.

**A stay is half-open `[checkIn, checkOut)`.** The nights occupied are `checkIn … checkOut-1`, so two
stays collide exactly when their night sets intersect. A guest leaving on the 5th and one arriving on
the 5th are not in conflict — that falls out of the model rather than needing an off-by-one
correction at every call site.

**Rooms are held before money moves.** A booking starts `PENDING_PAYMENT`. Charging first and then
grabbing a room fails in the expensive direction: money taken for a stay that cannot happen. The cost
is that an abandoned checkout would sterilise rooms, so every hold expires and a sweeper returns them
to sale.

**Double-booking is prevented by the database, not by application care.** `InventoryService.reserve`
takes `SELECT … FOR UPDATE` over every night **in ascending date order** before reading a single
number. The ordering is load-bearing: without it, a 3rd–5th booking racing a 4th–6th booking can
deadlock. Two passes — check every night, then mutate every night — mean in-memory state is never
half-applied.

**Idempotency rests on a unique constraint, not a lookup.** The key is claimed in the database
*before* the gateway is called, and flushed immediately. A check-then-act has an unavoidable window
between the check and the act; the constraint does not.

**A standalone hotel is a group holding one property.** There is no `isSingleProperty` flag and no
second code path — `standalone` is derived by counting, so it cannot drift, and a solo owner becoming
a chain is an `INSERT` rather than a migration.

**[→ DESIGN.md](DESIGN.md)** has the full reasoning, including the alternatives considered and
rejected.

---

## Architecture

```
com.rupeek.hotelbooking
├── domain/            the business, with no opinion about HTTP or Spring wiring
│   ├── model/         aggregates + status enums that own their transition tables
│   ├── vo/            Money, DateRange, Location, Amenity — immutable, self-validating
│   ├── policy/        PricingStrategy, CancellationPolicy family, registries
│   ├── search/        criteria + the composable PropertyFilter / RoomTypeFilter chains
│   ├── port/          outbound contracts: *Repository, PaymentGateway
│   └── exception/     the domain's own failure vocabulary
├── application/       use-case services; orchestration and transaction boundaries
├── infrastructure/    persistence adapters, mock gateways, wiring, sweeper
└── api/               REST controllers, DTOs, one global exception handler
```

Dependencies point **inwards**. `domain` knows nothing of `application`, `application` knows nothing
of `infrastructure` or `api`, and no service or entity imports `org.springframework.http`.

The one honest compromise: **entities carry JPA annotations.** A textbook hexagonal build would keep
`domain` framework-free and map to separate persistence entities. That doubles the class count for a
prototype, so instead the aggregates are rich objects that happen to be `@Entity`, while the
*repository ports* stay pure domain interfaces with adapters behind them. The dependency inversion
that matters is intact; the annotation purity is not.

---

## Testing

```bash
./mvnw test
```

Fast unit tests need no Spring context — a `Money`, a `Booking`, a `CancellationPolicy` and a
`PropertyFilter` can each be exercised with `new`. That they *can* be is the evidence the seams are
in the right places.

| Test | What it pins down |
|---|---|
| **`ConcurrentBookingIntegrationTest`** | 20 threads race for 1 room: exactly one wins, and the DB agrees. A second case runs staggered overlapping stays — the arrangement that deadlocks if lock ordering is wrong. A third races a payment against a cancellation, asserting a cancelled booking never leaves money taken and unrefunded. |
| **`PaymentIdempotencyIntegrationTest`** | No double charge, in both shapes: sequential retry and concurrent duplicates. Asserts against the **wallet balance**, not our own records — "did the money move twice?" should be answered by looking at the money. |
| `BookingLifecycleIntegrationTest` | search → book → pay → cancel; sold-out properties vanish from search for those dates only; a partly-available stay is refused atomically; an expired hold returns its rooms even when the payment that discovered it is rejected. |
| `CancellationPolicyTest` | Refund tiers asserted **on their boundaries** — 24 hours exactly vs one minute inside. Possible only because the clock is injected. |
| `BookingTest` / `BookingStatusTest` | Every illegal transition, attempted on a bare entity with no service in the way. |
| `DateRangeTest` | The half-open convention, including that check-out day is not a night. |

Three deliberate choices in the concurrency tests, because each is a way such a test can silently
prove nothing:

1. **No `@Transactional` on the test class** — it would enrol every thread in one shared transaction,
   removing the contention the test exists to create.
2. **A `CountDownLatch` start gate** — threads submitted to a pool otherwise start staggered enough
   that each booking finishes before the next begins, and the race never runs.
3. **The final assertion cross-checks the database against the bookings actually granted.** Asserting
   only `heldRooms <= totalRooms` would be worthless: the entity guarantees it unconditionally, and a
   lost update makes the counter *understate*, never overstate.

Time is injected everywhere (`java.time.Clock`); tests substitute a `MutableClock` they move by hand,
so hold expiry and refund boundaries are deterministic rather than flaky.

---

## Database and profiles

Both external services are **optional and off by default** — the service runs with zero
infrastructure. Each is behind a Spring profile, and switching one on changes configuration only. No
repository, service, entity or query differs between profiles; if swapping the database had required
touching `domain` or `application`, the repository ports would have been decoration.

| Service | Profile | Used for |
|---|---|---|
| **PostgreSQL 16** | `postgres` | Primary datastore instead of H2 |
| **Redis 7** | `redis` | A cluster-safe lock so the hold-expiry sweeper runs once, not once per instance |

```bash
docker compose up -d postgres redis        # or a native install — see SETUP.md
SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
```

### Creating the schema explicitly

The `postgres` profile uses `ddl-auto: update` and lets Hibernate build the tables — the zero-setup
path. `db/` holds the same schema written by hand, for when you would rather own it than infer it:

```bash
./db/setup.sh          # role, database, and every table; idempotent
```

| File | |
|---|---|
| [`db/schema.sql`](db/schema.sql) | 7 tables with named constraints, query-driven indexes, and CHECK constraints for the domain invariants |
| [`db/setup.sh`](db/setup.sh) | Role + database + schema in one command |

The DDL is not a guess at what the entities need — it is verified against them with
`-Dspring.jpa.hibernate.ddl-auto=validate`, which fails startup on any mismatch in table, column,
type or nullability.

What it adds over `ddl-auto`: Hibernate creates no indexes beyond those implied by primary and unique
keys, and PostgreSQL does not index foreign keys automatically. So there is an index per real access
path — `(city, locality)` for search, `(guest_email, created_at DESC)` for a guest's bookings, and a
*partial* index on `hold_expires_at WHERE status = 'PENDING_PAYMENT'`, since the sweeper only ever
reads unpaid holds. It also states the domain invariants as CHECK constraints, so
`held_rooms <= total_rooms` holds against a stray `UPDATE` and not only against code going through
the aggregates.

### Running the test suite against PostgreSQL

```bash
createdb -O hotelbooking hotelbooking_test
./mvnw test -Ppostgres-it
```

The Maven profile rather than `SPRING_PROFILES_ACTIVE=postgres`: the integration tests declare
`@ActiveProfiles("test")`, and that annotation outranks the environment variable — so exporting the
profile leaves the tests quietly running on H2 while looking like it worked. `-Ppostgres-it` sets the
datasource directly and uses a separate database, so a test run cannot drop the schema you were
demoing against.

---

## API

Full reference with runnable `curl` for every endpoint: **[API.md](API.md)**.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/property-groups` | Onboard an owner with one or many properties |
| `GET` | `/api/v1/property-groups/{groupId}` | Owner and everything under it |
| `POST` | `/api/v1/property-groups/{groupId}/properties` | Add a property to an existing owner |
| `GET` | `/api/v1/property-groups/cancellation-policies` | Which policies this deployment registered |
| `POST` | `/api/v1/properties/search` | Discovery — location, dates, party, price, amenities, rating |
| `POST` | `/api/v1/bookings` | Create a booking, holding inventory |
| `GET` | `/api/v1/bookings/{bookingId}` | One booking |
| `GET` | `/api/v1/bookings?guestEmail=…` | A guest's bookings |
| `POST` | `/api/v1/bookings/{bookingId}/payments` | Pay (requires `Idempotency-Key`) |
| `GET` | `/api/v1/bookings/{bookingId}/payments` | Payment attempt history |
| `POST` | `/api/v1/bookings/{bookingId}/cancellation` | Cancel, release rooms, settle refund |

One error shape everywhere, with a stable machine-readable `code` so clients never parse messages:

```json
{
  "code": "INVENTORY_UNAVAILABLE",
  "message": "Only 0 room(s) available on 2026-09-11 but 1 requested",
  "timestamp": "2026-08-22T10:15:30Z",
  "details": { "firstUnavailableNight": "2026-09-11" }
}
```

`409` is used wherever the request was *valid* but lost a race or hit a state rule — the client
should retry or change dates, not fix its payload.

---

## Assumptions

1. **No authentication or authorisation.** Out of scope per the brief. Any caller can act as any
   guest or owner; there is no tenancy check on `groupId`.
2. **Inventory is a count per (room type, night), not a named room** — so "give me room 402" cannot
   be expressed.
3. **Single currency (INR).** `Money` carries and enforces its currency and refuses to mix, so
   multi-currency is a data change rather than a rewrite — but no FX conversion exists.
4. **Dates are UTC, and a night is a calendar date.** A real system needs the *property's* timezone:
   check-in on 10 March means something different in Goa than in Denver.
5. **Nightly inventory is materialised 365 days ahead at onboarding.** Creating rows up front rather
   than lazily removes a second race, leaving the booking path with exactly one concurrency concern.
   Bookings beyond the horizon are refused with a clear message.
6. **Unpaid bookings hold rooms for 15 minutes**, then a sweeper returns them to sale.
7. **The gateways are mocks, and deterministically so.** An idempotency key starting with `DECLINE`
   is declined by the card gateway; the wallet declines on insufficient funds. A randomly-failing
   mock would make the failure-path tests as flaky as the mock.
8. **A declined payment consumes its idempotency key.** Retrying with the same key replays the
   decline — the same request must yield the same outcome. A genuine new attempt uses a new key.
9. **Search is a `POST`.** The criteria set is open-ended; encoding a growing structured object into
   a query string ends in `?amenities=WIFI&amenities=POOL&…` and a URL length limit. The cost is that
   searches are not URL-cacheable, which is moot for a result that depends on live inventory.
10. **Star rating and amenities are supplied at onboarding**, with no verification workflow.

---

## What I would do with more time

Ordered by what I would reach for first.

1. **An outbox for the refund.** The one real correctness gap: `CancellationService` calls the
   gateway inside the database transaction. If the refund succeeds and the commit then fails, our
   records disagree with the provider's. The fix is to persist the refund *intent*, settle it
   asynchronously, and reconcile — the same pattern the charge path needs for at-most-once guarantees
   under partial failure. Left out because a correct outbox is a bigger piece of machinery than the
   rest of this service, and faking it with a `try/catch` would be worse than naming the gap.
2. **Dynamic pricing.** `PricingStrategy` exists precisely so this is additive: a decorator chain of
   `WeekendSurcharge` → `LengthOfStayDiscount` → `OccupancyBasedYield` wrapping
   `StandardPricingStrategy`, each independently testable. The seam is built; only the
   implementations are missing.
3. **Pagination and sorting on search.** An unbounded list is fine for a prototype and not for a city
   with 4,000 hotels.
4. **Move the booking horizon off onboarding.** 365 rows per room type is fine at this scale and
   wasteful at real scale. A rolling window job plus `INSERT … ON CONFLICT DO NOTHING` for lazy
   creation removes the fixed cost.
5. **Amenities as reference data.** An enum gives type-safe filtering and costs a deploy to add
   "EV charging". A reference table with a cached lookup keeps the safety at the API boundary.
6. **Optimistic locking for inventory as a second mode.** `Booking` already carries `@Version`.
   Pessimistic row locks are the right default for high-contention inventory, but under low
   contention `@Version` plus bounded retry performs better; both behind `InventoryService` would
   make the trade measurable rather than argued.
7. **Property timezones**, so "check-in on the 10th" means the same thing to guest and hotel.
8. **Testcontainers**, so the PostgreSQL profile is exercised in CI rather than by a developer
   remembering `-Ppostgres-it`.
9. **Flyway** instead of `ddl-auto` on the Postgres profile. `db/schema.sql` is the schema; turning it
   into versioned migrations is the next step.

---

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `hotel-booking.inventory.booking-horizon-days` | `365` | How far ahead nightly inventory is created |
| `hotel-booking.booking.payment-hold-minutes` | `15` | How long an unpaid booking holds its rooms |
| `hotel-booking.booking.hold-sweep-interval-ms` | `60000` | How often lapsed holds are released |
| `hotel-booking.demo-data.enabled` | `true` (H2) / `false` (Postgres) | Seed demo properties at startup |

Under the `postgres` and `redis` profiles, read from the environment:

| Variable | Default |
|---|---|
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` |
| `POSTGRES_DB` | `hotelbooking` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `hotelbooking` / `hotelbooking` |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |

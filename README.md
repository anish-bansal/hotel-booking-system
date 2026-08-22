<div align="center">

# 🏨 Hotel Booking Service

**A backend for a hotel booking platform — onboard properties, search what's genuinely available, book, pay, and cancel.**

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Tests](https://img.shields.io/badge/tests-97%20passing-success.svg)](#-testing)

</div>

---

## 📖 Overview

A REST API for a hotel booking platform, built around the parts that are genuinely hard: preventing
two guests from booking the same room, keeping payments safe to retry, and putting extension points
where the business will actually change.

Runs with **zero setup** — H2 in-memory by default, PostgreSQL and Redis optional behind Spring
profiles.

## ✨ Features

- 🔍 **Property search** — by city, locality, dates and party size, with pluggable filters for price,
  amenities and star rating. Only genuinely available properties are returned.
- 🏢 **Property onboarding** — a standalone hotel and a fifty-property chain use the same code path;
  neither is a special case.
- 📅 **Booking with real inventory** — rooms are held per night, and double-booking is prevented by
  database row locks rather than hopeful application code.
- 💳 **Payments** — card, UPI and wallet behind one abstraction, with idempotency so a retry can
  never double-charge.
- ↩️ **Cancellation** — pluggable refund policies (flexible, moderate, non-refundable); rooms always
  return to sale.
- ⏱️ **Automatic hold expiry** — abandoned checkouts release their rooms, safely across multiple
  instances.

## 🛠️ Tech Stack

| | |
|---|---|
| **Language** | Java 17 |
| **Framework** | Spring Boot 3.4 (Web, Data JPA, Validation, Actuator) |
| **Database** | H2 (default) · PostgreSQL 16 (optional) |
| **Cache / Lock** | Redis 7 (optional) |
| **Build** | Maven (wrapper included) |
| **Testing** | JUnit 5, AssertJ, Spring Boot Test |
| **Docs** | springdoc-openapi (Swagger UI) |

---

## 🚀 Getting Started

### Prerequisites

**A JDK 17 or newer — that's it.** Maven comes from the committed wrapper.

### Run it

```bash
git clone https://github.com/anish-bansal/hotel-booking-system.git
cd hotel-booking-system
./mvnw spring-boot:run
```

The service starts on **http://localhost:8080** with demo properties already seeded, so the API is
explorable immediately.

### Run the tests

```bash
./mvnw test
```

### One command, end to end

```bash
./run.sh
```

Builds, starts the server, waits for a real readiness signal, then drives the whole
search → book → pay → cancel flow through the live API with a pass/fail per step.

### Explore

| | |
|---|---|
| 📘 Swagger UI | http://localhost:8080/swagger-ui.html |
| 📄 OpenAPI JSON | http://localhost:8080/v3/api-docs |
| ❤️ Health | http://localhost:8080/actuator/health |
| 🔌 What's wired | http://localhost:8080/api/v1/system/capabilities |

---

## 📡 API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/property-groups` | Onboard an owner with one or many properties |
| `GET` | `/api/v1/property-groups/{groupId}` | Get an owner and everything under it |
| `POST` | `/api/v1/property-groups/{groupId}/properties` | Add a property to an existing owner |
| `GET` | `/api/v1/property-groups/cancellation-policies` | List supported cancellation policies |
| `POST` | `/api/v1/properties/search` | Search available properties |
| `POST` | `/api/v1/bookings` | Create a booking (holds inventory) |
| `GET` | `/api/v1/bookings/{bookingId}` | Get a booking |
| `GET` | `/api/v1/bookings?guestEmail=…` | List a guest's bookings |
| `POST` | `/api/v1/bookings/{bookingId}/payments` | Pay for a booking |
| `GET` | `/api/v1/bookings/{bookingId}/payments` | Payment attempt history |
| `POST` | `/api/v1/bookings/{bookingId}/cancellation` | Cancel, refund, and release rooms |

**Quick example**

```bash
curl -s -X POST http://localhost:8080/api/v1/properties/search \
  -H 'Content-Type: application/json' \
  -d '{ "city": "Bengaluru", "checkIn": "2026-10-06", "checkOut": "2026-10-09", "guests": 2 }'
```

📚 **[Full API reference →](API.md)** — every endpoint with runnable `curl` and real responses.

### Postman

Import [`postman_collection.json`](postman_collection.json) and hit **Run**. 22 requests that chain
their own ids, so nothing needs pasting by hand. It also runs headless as a test suite:

```bash
npx newman run postman_collection.json
```

---

## 🏗️ Project Structure

```
src/main/java/com/rupeek/hotelbooking
├── domain/            business logic, with no opinion about HTTP or Spring
│   ├── model/         aggregates + status enums owning their transition tables
│   ├── vo/            Money, DateRange, Location — immutable and self-validating
│   ├── policy/        pricing and cancellation strategies
│   ├── search/        search criteria and the composable filter chains
│   └── port/          outbound contracts (repositories, payment gateway)
├── application/       use-case services and transaction boundaries
├── infrastructure/    persistence adapters, mock gateways, config
└── api/               REST controllers, DTOs, global exception handler
```

Dependencies point **inwards** — `domain` knows nothing of Spring Web or the database.

---

## 🧪 Testing

```bash
./mvnw test                    # 97 tests on H2
./mvnw test -Ppostgres-it      # the same suite against real PostgreSQL
```

Highlights:

- **`ConcurrentBookingIntegrationTest`** — 20 threads race for one room; exactly one wins, and the
  database is checked to confirm it.
- **`PaymentIdempotencyIntegrationTest`** — no double charge, verified against the *wallet balance*
  rather than our own records.
- **`CancellationPolicyTest`** — refund tiers asserted on their exact boundaries, using an injected
  clock rather than real time.

---

## 🗄️ Database

Runs on **H2 in-memory** by default — no setup at all.

For PostgreSQL:

```bash
./db/setup.sh                                          # role, database, and tables
SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run
```

[`db/schema.sql`](db/schema.sql) holds the hand-written DDL: 7 tables with named constraints,
indexes derived from the actual queries, and CHECK constraints enforcing the domain rules at the
database level.

| Profile | Purpose |
|---|---|
| *(none)* | H2 in-memory, demo data seeded |
| `postgres` | PostgreSQL instead of H2 |
| `redis` | Cluster-safe lock for the hold-expiry sweeper |

---

## ⚙️ Configuration

| Property | Default | Purpose |
|---|---|---|
| `hotel-booking.inventory.booking-horizon-days` | `365` | How far ahead inventory is created |
| `hotel-booking.booking.payment-hold-minutes` | `15` | How long an unpaid booking holds rooms |
| `hotel-booking.booking.hold-sweep-interval-ms` | `60000` | How often lapsed holds are released |
| `hotel-booking.demo-data.enabled` | `true` (H2) | Seed demo properties at startup |

---

## 📚 Documentation

| | |
|---|---|
| **[API.md](API.md)** | Every endpoint, with runnable `curl` and real captured responses |
| **[DESIGN.md](DESIGN.md)** | Domain model, state machine, concurrency mechanism, and the trade-offs taken |
| **[SETUP.md](SETUP.md)** | Toolchain setup and troubleshooting |

---

## 📝 Notes

This is a prototype built for a design discussion, not a production system. It goes deep on
concurrency, payment idempotency and extensibility, and deliberately stops short of authentication,
multi-currency and production migrations. **[DESIGN.md](DESIGN.md)** is explicit about where the
line is and why.

# API Reference

Every request below is a runnable `curl`, and every response is real output captured from the
service — not illustrative JSON. Run them top to bottom and they compose into a full
onboard → search → book → pay → cancel journey.

**Base URL** `http://localhost:8080/api/v1`
**Interactive** [Swagger UI](http://localhost:8080/swagger-ui.html) · [OpenAPI JSON](http://localhost:8080/v3/api-docs)

**Postman** Import [`postman_collection.json`](postman_collection.json) — see
[Using Postman](#using-postman) below.

---

## Contents

| | Endpoint | |
|---|---|---|
| [1](#1-what-is-wired) | `GET /system/capabilities` | What this instance discovered at startup |
| [2](#2-supported-cancellation-policies) | `GET /property-groups/cancellation-policies` | Policy codes you may onboard with |
| [3](#3-onboard-an-owner) | `POST /property-groups` | Onboard an owner and their properties |
| [4](#4-add-a-property-to-an-existing-owner) | `POST /property-groups/{groupId}/properties` | Grow a standalone owner into a chain |
| [5](#5-read-an-owner) | `GET /property-groups/{groupId}` | Read an owner and everything beneath it |
| [6](#6-search-for-available-properties) | `POST /properties/search` | Discovery with filters |
| [7](#7-create-a-booking) | `POST /bookings` | Hold rooms, unpaid |
| [8](#8-read-a-booking) | `GET /bookings/{bookingId}` | Read one booking |
| [9](#9-list-a-guests-bookings) | `GET /bookings?guestEmail=` | A guest's history |
| [10](#10-pay-for-a-booking) | `POST /bookings/{bookingId}/payments` | Pay — idempotent |
| [11](#11-list-payment-attempts) | `GET /bookings/{bookingId}/payments` | Every attempt for a booking |
| [12](#12-cancel-a-booking) | `POST /bookings/{bookingId}/cancellation` | Cancel, refund, release rooms |

---

## Using Postman

Import **[`postman_collection.json`](postman_collection.json)** — 22 requests in 7 folders, covering
every endpoint plus the idempotent-replay, declined-payment and error paths.

**Postman → Import → File →** select `postman_collection.json`. That is the whole setup; the
collection carries its own variables, so the optional
[`postman_environment.json`](postman_environment.json) is only useful if you want to keep several
environments (local, staging) side by side.

Then hit **Run** on the collection. Every request passes in order without pasting anything by hand:

- **Ids chain automatically.** Onboarding saves `groupId`, `propertyId` and `roomTypeId`; booking
  saves `bookingId`; paying saves `paymentId`. Each is written to a collection variable by a test
  script and picked up by the next request.
- **Dates never go stale.** A collection-level pre-request script sets `checkIn`/`checkOut` to
  today + 45 and today + 48 on every run, so a stay is always in the bookable future.
- **Idempotency keys are generated per attempt**, so the success and replay requests genuinely share
  a key while the declined one gets its own.
- **Re-runnable.** Each run onboards its own owner, so running it repeatedly neither collides nor
  depends on state from last time.

Only `baseUrl` is worth changing — it defaults to `http://localhost:8080/api/v1`.

### Running it headless

The collection doubles as an API test suite. With Node installed:

```bash
npx newman run postman_collection.json
```

```
requests      22    0 failed
assertions    57    0 failed
```

The 57 assertions are not just status codes — they check that the price cap actually excludes the
over-budget room, that money crosses the wire as a string, that a replayed payment returns the
original `paymentId` with `idempotentReplay: true`, and that a double cancellation is refused.

### Or import the OpenAPI spec instead

If you would rather have something that tracks the code automatically:

**Postman → Import → Link →** `http://localhost:8080/v3/api-docs`

That regenerates from the controllers, so it can never drift. The trade is that you get empty
request bodies, no chaining between requests and no assertions — good for exploring the surface,
weaker for actually exercising a flow.

---

## Conventions

**Money is a JSON string, never a number.**

```json
{ "amount": "19500.00", "currency": "INR" }
```

The domain keeps money in `BigDecimal` so no rounding creeps in, and emitting it as a bare JSON
number would hand that straight back — JSON numbers have no defined precision, and every JavaScript
client parses them into an IEEE-754 double. A string crosses the wire exactly as scaled.

**Dates are half-open: `[checkIn, checkOut)`.** A stay occupies the nights `checkIn` through
`checkOut - 1`. The checkout night is *not* held, so a guest leaving on the 9th and one arriving on
the 9th do not conflict. `2026-10-06 → 2026-10-09` is three nights.

**City and locality are stored lower-cased.** `"Mysuru"` goes in, `"mysuru"` comes back. Searching is
case-insensitive as a result; display names come from `propertyName`, which preserves its casing.

**Timestamps are UTC ISO-8601** (`2026-08-22T15:11:52.274166Z`). Dates are plain `YYYY-MM-DD`.

**Paying requires an `Idempotency-Key` header.** See [§10](#10-pay-for-a-booking).

### Following along

The examples chain through shell variables, so run this once first and then paste each block as-is.
Ids are captured from the responses rather than pasted, and the dates are generated, so nothing here
goes stale:

```bash
CHECK_IN=$(date -v+45d +%F 2>/dev/null || date -d '+45 days' +%F)   # macOS || GNU
CHECK_OUT=$(date -v+48d +%F 2>/dev/null || date -d '+48 days' +%F)
```

`GROUP_ID`, `PROPERTY_ID`, `ROOM_TYPE_ID` and `BOOKING_ID` are set for you by the blocks below.
Extraction uses `jq`; on macOS, `brew install jq`.

---

## 1. What is wired

Every extension point — gateways, policies, filters — is wired by bean discovery rather than a
hard-coded list. This endpoint exists because discovery is wonderful until something silently fails
to be discovered, at which point the only symptom is a feature quietly not working.

```bash
curl -s http://localhost:8080/api/v1/system/capabilities
```

```json
{
  "activeProfiles": ["postgres"],
  "database": "PostgreSQL",
  "paymentMethods": ["UPI", "WALLET", "CARD"],
  "cancellationPolicies": ["MODERATE", "NON_REFUNDABLE", "FLEXIBLE"],
  "searchFilters": ["PriceRangeFilter", "AmenityFilter", "StarRatingFilter"],
  "roomTypeFilters": ["RoomTypePriceFilter"],
  "sweepLock": "in-process (single instance only)",
  "settings": { "bookingHorizonDays": 365, "paymentHoldMinutes": 15 }
}
```

`database` reports the *kind*, never the JDBC URL — a connection string carries a host, a port and
sometimes credentials, and this endpoint is unauthenticated.

The two filter chains are listed separately because they act on different units: `searchFilters`
decides which properties appear at all, `roomTypeFilters` decides which rooms within a matching
property may be offered.

---

## 2. Supported cancellation policies

Call this before onboarding — `cancellationPolicyCode` is validated at onboarding time, so an unknown
code fails here rather than at some guest's cancellation months later.

```bash
curl -s http://localhost:8080/api/v1/property-groups/cancellation-policies
```

```json
{ "supported": ["NON_REFUNDABLE", "FLEXIBLE", "MODERATE"] }
```

| Code | Terms |
|---|---|
| `FLEXIBLE` | Full refund until 24 hours before check-in |
| `MODERATE` | Full refund 7+ days before; 50% within 7 days; none within 24 hours |
| `NON_REFUNDABLE` | No refund — but the rooms are still released back to sale |

---

## 3. Onboard an owner

One endpoint onboards both a standalone hotel and a fifty-property chain. There is no
`isSingleProperty` flag and no second code path: a standalone hotel is simply a group whose property
list has one element, and `standalone` in the response is derived by counting rather than stored.

```bash
curl -s -X POST http://localhost:8080/api/v1/property-groups \
  -H 'Content-Type: application/json' \
  -o /tmp/owner.json \
  -d '{
    "groupName": "Coastline Hotels",
    "contactEmail": "owner@coastline.example",
    "properties": [{
      "name": "Coastline Grand",
      "city": "Mysuru",
      "locality": "Gokulam",
      "addressLine": "12 Palace Road",
      "starRating": 5,
      "amenities": ["WIFI", "POOL", "SPA"],
      "cancellationPolicyCode": "FLEXIBLE",
      "roomTypes": [
        { "name": "Deluxe King", "maxOccupancy": 2, "totalRooms": 8, "basePricePerNight": 6500 },
        { "name": "Penthouse",   "maxOccupancy": 4, "totalRooms": 1, "basePricePerNight": 28000 }
      ]
    }]
  }'

# Capture the generated ids for the requests that follow.
GROUP_ID=$(jq -r .id                             /tmp/owner.json)
PROPERTY_ID=$(jq -r .properties[0].id            /tmp/owner.json)
ROOM_TYPE_ID=$(jq -r .properties[0].roomTypes[0].id /tmp/owner.json)
```

**`201 Created`**

```json
{
  "id": "79deb057-a81a-4177-a268-2bd56f14d7de",
  "name": "Coastline Hotels",
  "contactEmail": "owner@coastline.example",
  "standalone": true,
  "propertyCount": 1,
  "properties": [{
    "id": "f54574cc-56e5-423c-8bc4-d64e4495536c",
    "name": "Coastline Grand",
    "city": "mysuru",
    "locality": "gokulam",
    "addressLine": "12 Palace Road",
    "starRating": 5,
    "amenities": ["WIFI", "POOL", "SPA"],
    "cancellationPolicyCode": "FLEXIBLE",
    "roomTypes": [{
      "id": "dc766ce8-55ff-4e04-8aeb-cc7e73c7f918",
      "name": "Deluxe King",
      "maxOccupancy": 2,
      "totalRooms": 8,
      "basePricePerNight": { "amount": "6500.00", "currency": "INR" }
    }]
  }]
}
```

Onboarding also opens nightly inventory for every room type out to the booking horizon (365 nights by
default), so a property is bookable the moment it exists.

| Field | Rules |
|---|---|
| `groupName` | required |
| `contactEmail` | required, must be a valid email |
| `properties` | at least one; each needs at least one room type |
| `starRating` | 1–5 |
| `amenities` | optional, from the [amenity list](#amenities) |
| `basePricePerNight` | > 0, INR |

---

## 4. Add a property to an existing owner

This is how a group grows — and how a solo owner becomes a chain. No migration, no re-onboarding,
because they were always the same shape.

```bash
curl -s -X POST "http://localhost:8080/api/v1/property-groups/$GROUP_ID/properties" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Coastline Loft",
    "city": "Mysuru",
    "locality": "Jayalakshmipuram",
    "addressLine": "5 Cross",
    "starRating": 3,
    "amenities": ["WIFI"],
    "cancellationPolicyCode": "MODERATE",
    "roomTypes": [
      { "name": "Studio", "maxOccupancy": 2, "totalRooms": 4, "basePricePerNight": 2800 }
    ]
  }'
```

**`201 Created`** — returns the new property, including the generated room-type ids.

Note the second property uses `MODERATE` while the first uses `FLEXIBLE`: the policy is data on the
property, so different hotels in one chain can run different terms.

---

## 5. Read an owner

```bash
curl -s "http://localhost:8080/api/v1/property-groups/$GROUP_ID"
```

**`200 OK`** — same shape as [§3](#3-onboard-an-owner), now reflecting both properties:

```json
{
  "id": "79deb057-a81a-4177-a268-2bd56f14d7de",
  "name": "Coastline Hotels",
  "standalone": false,
  "propertyCount": 2,
  "properties": [ /* ... */ ]
}
```

`standalone` flipped to `false` on its own — nothing set it.

---

## 6. Search for available properties

`POST` rather than `GET`, because the criteria are a structured object with a nested amenity set —
which does not express well as a query string.

Only `city`, `checkIn`, `checkOut` and `guests` are required; every filter is optional and simply
not applied when omitted.

```bash
curl -s -X POST http://localhost:8080/api/v1/properties/search \
  -H 'Content-Type: application/json' \
  -d "{
    \"city\": \"Mysuru\",
    \"locality\": \"Gokulam\",
    \"checkIn\":  \"$CHECK_IN\",
    \"checkOut\": \"$CHECK_OUT\",
    \"guests\": 2,
    \"maxNightlyPrice\": 10000,
    \"amenities\": [\"WIFI\", \"POOL\"],
    \"minStarRating\": 4
  }"
```

**`200 OK`**

```json
{
  "resultCount": 1,
  "results": [{
    "propertyId": "f54574cc-56e5-423c-8bc4-d64e4495536c",
    "propertyName": "Coastline Grand",
    "city": "mysuru",
    "locality": "gokulam",
    "starRating": 5,
    "amenities": ["POOL", "SPA", "WIFI"],
    "cancellationPolicy": "Free cancellation until 24 hours before check-in",
    "availableRoomTypes": [{
      "roomTypeId": "dc766ce8-55ff-4e04-8aeb-cc7e73c7f918",
      "name": "Deluxe King",
      "maxOccupancy": 2,
      "roomsRequiredForParty": 1,
      "roomsAvailable": 8,
      "nightlyRate":  { "amount": "6500.00",  "currency": "INR" },
      "totalForStay": { "amount": "19500.00", "currency": "INR" }
    }]
  }]
}
```

**The ₹28,000 Penthouse is absent.** `maxNightlyPrice` narrows the *rooms offered*, not just the
hotels listed — a property qualifying on its cheapest room does not get to advertise its most
expensive. Drop `maxNightlyPrice` and both room types return.

**Only genuinely bookable results appear.** A property is returned only if some room type has enough
rooms free on *every* night of the stay and can seat the party. `roomsAvailable` is the lowest
availability across the range, not an average.

Availability here is advisory — a room shown as free may be gone by the time you book, which is why
[§7](#7-create-a-booking) re-checks under a row lock.

| Filter | Effect |
|---|---|
| `locality` | narrows within the city |
| `minNightlyPrice` / `maxNightlyPrice` | applied to properties **and** to individual room types |
| `amenities` | property must have **every** amenity listed, not any |
| `minStarRating` | a floor, not an exact match |

---

## 7. Create a booking

Takes the inventory **before** any money moves, so the booking starts in `PENDING_PAYMENT`. The
alternative — charge first, then try to grab a room — fails in the expensive direction: if the rooms
are gone you have taken money for a stay that cannot happen.

The cost is that an abandoned checkout would sterilise saleable rooms, so every hold carries
`holdExpiresAt` (15 minutes by default) after which a sweeper returns the rooms to sale.

```bash
curl -s -X POST http://localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -o /tmp/booking.json \
  -d "{
    \"propertyId\":  \"$PROPERTY_ID\",
    \"roomTypeId\":  \"$ROOM_TYPE_ID\",
    \"guestName\":   \"Asha Rao\",
    \"guestEmail\":  \"asha@example.com\",
    \"checkIn\":     \"$CHECK_IN\",
    \"checkOut\":    \"$CHECK_OUT\",
    \"guests\": 2,
    \"rooms\": 1
  }"

BOOKING_ID=$(jq -r .id /tmp/booking.json)
```

**`201 Created`**

```json
{
  "id": "d8ccc91d-5a03-47fc-bdf6-485e9bb3df15",
  "propertyId": "f54574cc-56e5-423c-8bc4-d64e4495536c",
  "roomTypeId": "dc766ce8-55ff-4e04-8aeb-cc7e73c7f918",
  "guestName": "Asha Rao",
  "guestEmail": "asha@example.com",
  "checkIn": "2026-10-06",
  "checkOut": "2026-10-09",
  "nights": 3,
  "guests": 2,
  "rooms": 1,
  "totalAmount": { "amount": "19500.00", "currency": "INR" },
  "status": "PENDING_PAYMENT",
  "allowedNextStates": ["CONFIRMED", "CANCELLED", "EXPIRED"],
  "createdAt": "2026-08-22T15:11:52.274166Z",
  "holdExpiresAt": "2026-08-22T15:26:52.274166Z",
  "confirmedAt": null,
  "cancelledAt": null,
  "refundedAmount": null
}
```

`allowedNextStates` is the state machine telling you what it will accept next, so a client never has
to hard-code the lifecycle.

**`409 Conflict`** with `INVENTORY_UNAVAILABLE` if the rooms are gone. The request was well formed —
it lost a race for a finite resource — so the correct client response is different dates, not a fixed
payload. The `details` name the first night that could not be satisfied.

Concurrent requests for the last room are serialised by a `SELECT … FOR UPDATE` over every night of
the stay, acquired in ascending date order. Exactly one caller gets `201`; the rest get `409`.

---

## 8. Read a booking

```bash
curl -s "http://localhost:8080/api/v1/bookings/$BOOKING_ID"
```

**`200 OK`** — same shape as above. **`404`** with `NOT_FOUND` for an unknown id.

---

## 9. List a guest's bookings

```bash
curl -s "http://localhost:8080/api/v1/bookings?guestEmail=asha@example.com"
```

**`200 OK`** — an array of bookings, newest first.

---

## 10. Pay for a booking

The payment outcome drives the booking state: a successful charge confirms the booking, a declined
one leaves it `PENDING_PAYMENT` so the guest can try another method while the hold lasts.

**`Idempotency-Key` is required.** Payment is the one operation where a retry costs real money, and
it is also the most likely to be retried — a client that times out mid-charge genuinely does not know
whether the money moved.

```bash
IDEM_KEY=$(uuidgen)

curl -s -X POST "http://localhost:8080/api/v1/bookings/$BOOKING_ID/payments" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{ "method": "CARD", "payerReference": "4111111111111111" }'
```

**`201 Created`**

```json
{
  "paymentId": "df979560-3dd4-4623-b6c9-22e22a045fe5",
  "bookingId": "d8ccc91d-5a03-47fc-bdf6-485e9bb3df15",
  "method": "CARD",
  "amount": { "amount": "19500.00", "currency": "INR" },
  "status": "SUCCESSFUL",
  "gatewayReference": "CARD-6b30a597-f3c3-42eb-9560-9bc99ed2fbb9",
  "failureReason": null,
  "settledAt": "2026-08-22T15:11:52.297408Z",
  "idempotentReplay": false,
  "booking": { "status": "CONFIRMED", "allowedNextStates": ["CANCELLED"], "...": "..." }
}
```

### Retrying with the same key

Send the identical request again:

```bash
curl -s -X POST "http://localhost:8080/api/v1/bookings/$BOOKING_ID/payments" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM_KEY" \
  -d '{ "method": "CARD", "payerReference": "4111111111111111" }'
```

**`200 OK`** — note the status code, and:

```json
{ "paymentId": "df979560-3dd4-4623-b6c9-22e22a045fe5", "idempotentReplay": true }
```

Same `paymentId`, `201` became `200`, and `idempotentReplay` is now `true`. **The gateway was not
called a second time** and no second payment record exists.

A *concurrent* duplicate — a double-click rather than a sequential retry — gets **`409`** with
`DUPLICATE_REQUEST`, because a unique constraint on the key lets exactly one insert through. Retrying
then takes the replay path above.

A declined charge also consumes its key: retrying replays the decline, which is the correct reading
of idempotency. A genuine second attempt needs a new key.

| Field | Notes |
|---|---|
| `method` | `CARD`, `UPI` or `WALLET` — see [payment methods](#payment-methods) |
| `payerReference` | optional, free text — card number, VPA, wallet id |

Paying a booking that is already `CONFIRMED` returns **`409`** `ILLEGAL_STATE_TRANSITION`. Paying one
whose hold has expired returns **`400`**, and the rooms are released on the spot.

---

## 11. List payment attempts

Every attempt for a booking, newest first — including failed ones, which is what makes a decline
followed by a successful retry legible after the fact.

```bash
curl -s "http://localhost:8080/api/v1/bookings/$BOOKING_ID/payments"
```

**`200 OK`**

```json
[{
  "id": "df979560-3dd4-4623-b6c9-22e22a045fe5",
  "method": "CARD",
  "amount": { "amount": "19500.00", "currency": "INR" },
  "status": "SUCCESSFUL",
  "gatewayReference": "CARD-6b30a597-f3c3-42eb-9560-9bc99ed2fbb9",
  "failureReason": null
}]
```

---

## 12. Cancel a booking

Releases the rooms, then settles whatever refund the property's policy allows. The two are
independent: a `NON_REFUNDABLE` booking still gives its rooms back, because leaving them locked up
for a stay nobody will turn up for punishes the hotel for the guest's cheap rate.

```bash
curl -s -X POST "http://localhost:8080/api/v1/bookings/$BOOKING_ID/cancellation"
```

**`200 OK`**

```json
{
  "booking": {
    "status": "CANCELLED",
    "allowedNextStates": [],
    "cancelledAt": "2026-08-22T15:11:52.327480Z",
    "refundedAmount": { "amount": "19500.00", "currency": "INR" },
    "...": "..."
  },
  "appliedPolicy": "FLEXIBLE",
  "refundAmount": { "amount": "19500.00", "currency": "INR" },
  "refundReason": "Cancelled more than 24 hours before check-in: full refund",
  "roomsReleased": 1
}
```

`refundReason` states which rule fired, so a partial refund is explicable to the guest without
reading the policy source.

Cancelling an unpaid booking succeeds with a zero refund — there is nothing to give back. Cancelling
twice returns **`409`**:

```json
{
  "code": "ILLEGAL_STATE_TRANSITION",
  "message": "Booking cannot move from CANCELLED to CANCELLED",
  "timestamp": "2026-08-22T15:11:52.348579Z"
}
```

The rooms are discoverable again immediately — re-run [§6](#6-search-for-available-properties) and
`roomsAvailable` is back up.

---

## Errors

Every error shares one shape:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "checkOut (2026-10-06) must be strictly after checkIn (2026-10-09)",
  "timestamp": "2026-08-22T15:11:52.356110Z"
}
```

Field-level failures add `details`:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request payload failed validation",
  "timestamp": "2026-08-22T15:11:52.359586Z",
  "details": {
    "guests": "must be greater than or equal to 1",
    "guestEmail": "must be a well-formed email address",
    "guestName": "must not be blank"
  }
}
```

| Status | `code` | Means |
|---|---|---|
| `400` | `VALIDATION_FAILED` | Bad input — dates, party size, missing fields. Fix the payload. |
| `400` | `INVALID_ARGUMENT` | Rejected by a value object, e.g. a currency mismatch |
| `404` | `NOT_FOUND` | No such booking, property group, or room type |
| `409` | `INVENTORY_UNAVAILABLE` | Rooms gone for at least one night. `details.firstUnavailableNight` names which. |
| `409` | `ILLEGAL_STATE_TRANSITION` | The lifecycle forbids it — paying a confirmed booking, cancelling twice |
| `409` | `DUPLICATE_REQUEST` | Concurrent request with the same `Idempotency-Key`. Retry to get its result. |
| `409` | `CONCURRENT_MODIFICATION` | Another request changed this booking first. Re-read and retry. |
| `402` | `PAYMENT_FAILED` | The gateway declined a refund |
| `500` | `INTERNAL_ERROR` | Unexpected. Deliberately returns no detail — an internal message can leak SQL. |

`409` is used rather than `400` wherever the request was *valid* but lost a race or hit a state rule:
the client should retry or change dates, not fix its payload.

---

## Reference data

### Payment methods

All three are mocked behind one `PaymentGateway` abstraction, and each fails differently so the
decline path is reachable without a real provider:

| Method | Mock behaviour |
|---|---|
| `CARD` | Declines when the **`Idempotency-Key`** starts with `DECLINE` (case-insensitive). Otherwise succeeds. |
| `UPI` | Always succeeds. |
| `WALLET` | Declines with `Insufficient wallet balance` once the stay costs more than the mock wallet's remaining balance. |

Note the card trigger is the **header**, not `payerReference` — the key is what the gateway sees, so
a declined attempt consumes its key exactly like a successful one:

This needs a booking in `PENDING_PAYMENT`, so make a fresh one rather than reusing `$BOOKING_ID` —
by this point in the walkthrough that booking has been cancelled:

```bash
curl -s -X POST http://localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' -o /tmp/decline-booking.json \
  -d "{
    \"propertyId\": \"$PROPERTY_ID\", \"roomTypeId\": \"$ROOM_TYPE_ID\",
    \"guestName\": \"Declined Guest\", \"guestEmail\": \"declined@example.com\",
    \"checkIn\": \"$CHECK_IN\", \"checkOut\": \"$CHECK_OUT\", \"guests\": 2, \"rooms\": 1
  }"

DECLINE_BOOKING_ID=$(jq -r .id /tmp/decline-booking.json)

curl -s -X POST "http://localhost:8080/api/v1/bookings/$DECLINE_BOOKING_ID/payments" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: DECLINE-$(uuidgen)" \
  -d '{ "method": "CARD", "payerReference": "4111111111111111" }'
```

**`201 Created`** — a decline is a recorded outcome, not a transport error:

```json
{
  "status": "FAILED",
  "failureReason": "Card declined by issuer",
  "gatewayReference": null,
  "booking": { "status": "PENDING_PAYMENT", "...": "..." }
}
```

The booking stays `PENDING_PAYMENT` so the guest can try another method while the hold lasts. Retry
with a **new** key — reusing the declined one replays the decline.

### Amenities

`WIFI` · `POOL` · `GYM` · `SPA` · `PARKING` · `RESTAURANT` · `BAR` · `AIR_CONDITIONING` ·
`PET_FRIENDLY` · `AIRPORT_SHUTTLE` · `BREAKFAST_INCLUDED` · `WHEELCHAIR_ACCESSIBLE`

### Booking lifecycle

```
PENDING_PAYMENT ──payment ok──▶ CONFIRMED ──stay over──▶ COMPLETED
      │                             │
      ├──guest cancels──▶ CANCELLED ◀┘
      └──hold lapses────▶ EXPIRED
```

`CANCELLED`, `EXPIRED` and `COMPLETED` are terminal. Any transition not drawn above is rejected with
`409 ILLEGAL_STATE_TRANSITION`.

### Payment statuses

`INITIATED` → `SUCCESSFUL` → `REFUNDED`, or `INITIATED` → `FAILED`.

### Limits

| | |
|---|---|
| Payment hold | 15 minutes (`hotel-booking.booking.payment-hold-minutes`) |
| Booking horizon | 365 nights ahead (`hotel-booking.inventory.booking-horizon-days`) |
| Maximum stay | 30 nights |
| Currency | INR only |

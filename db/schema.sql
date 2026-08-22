-- =================================================================================================
-- Hotel Booking Service — PostgreSQL schema
--
-- Hand-authored DDL, equivalent to what Hibernate generates from the entity model but explicit
-- about the three things `ddl-auto` will not give you:
--
--   1. Named constraints. Hibernate emits `fk6du7hs4i3r3ply5kmrcl68bvp`. When that fires at 3am the
--      name is the only clue you get, so it should say what broke.
--   2. Indexes on the paths the code actually queries. Hibernate creates none beyond those implied
--      by primary keys and unique constraints, and PostgreSQL — unlike MySQL — does not index
--      foreign-key columns automatically. Every index below is justified against a real query.
--   3. CHECK constraints for domain invariants. The aggregates enforce these in Java; repeating
--      them here means a bad row cannot be written by a migration, a fixture, or a fat-fingered
--      psql session either.
--
-- Verified against the entity model with `spring.jpa.hibernate.ddl-auto=validate`, which fails
-- startup on any mismatch in table, column, type or nullability.
--
-- Usage:
--   createdb -O hotelbooking hotelbooking
--   psql -d hotelbooking -f db/schema.sql
--
-- Idempotent: safe to re-run. Drops are ordered children-first to respect foreign keys.
-- =================================================================================================

BEGIN;

-- The DROP IF EXISTS block below is expected to find nothing on a fresh database. Silencing NOTICE
-- keeps that normal case quiet, so anything this script does print is worth reading.
SET LOCAL client_min_messages TO WARNING;

DROP TABLE IF EXISTS payment          CASCADE;
DROP TABLE IF EXISTS booking          CASCADE;
DROP TABLE IF EXISTS room_inventory   CASCADE;
DROP TABLE IF EXISTS room_type        CASCADE;
DROP TABLE IF EXISTS property_amenity CASCADE;
DROP TABLE IF EXISTS property         CASCADE;
DROP TABLE IF EXISTS property_group   CASCADE;


-- -------------------------------------------------------------------------------------------------
-- property_group — the owner account.
--
-- There is no `is_standalone` column, and that is the point. A standalone hotel is a group holding
-- exactly one property; "standalone" is derived by counting, so it can never drift out of sync with
-- reality, and a solo owner becoming a chain is an INSERT rather than a migration.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE property_group (
    id            uuid         NOT NULL,
    name          varchar(255) NOT NULL,
    contact_email varchar(255) NOT NULL,

    CONSTRAINT pk_property_group PRIMARY KEY (id)
);


-- -------------------------------------------------------------------------------------------------
-- property — one hotel, always owned by a group.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE property (
    id                       uuid         NOT NULL,
    group_id                 uuid         NOT NULL,
    name                     varchar(255) NOT NULL,
    city                     varchar(255) NOT NULL,
    locality                 varchar(255) NOT NULL,
    address_line             varchar(255) NOT NULL,
    star_rating              integer      NOT NULL,
    cancellation_policy_code varchar(255) NOT NULL,

    CONSTRAINT pk_property           PRIMARY KEY (id),
    CONSTRAINT fk_property_group     FOREIGN KEY (group_id) REFERENCES property_group (id),
    CONSTRAINT ck_property_star_rating CHECK (star_rating BETWEEN 1 AND 5)
);

-- Search's only indexed stage: `findByLocationCity` and `findByLocationCityAndLocationLocality`.
-- One composite index serves both, because a leading-column match is usable on its own — a separate
-- index on (city) would be redundant. City and locality are stored lower-cased by the Location value
-- object, so this is a plain equality index with no need for a functional expression.
CREATE INDEX ix_property_city_locality ON property (city, locality);

-- PostgreSQL does not index foreign keys automatically. Without this, loading a group's properties
-- (and the ON DELETE check for a group) is a sequential scan of every property on the platform.
CREATE INDEX ix_property_group_id ON property (group_id);


-- -------------------------------------------------------------------------------------------------
-- property_amenity — @ElementCollection of the Amenity enum.
--
-- The CHECK mirrors the enum. It is deliberate duplication: adding an amenity means touching this
-- list, which is a small, visible cost that buys a database which cannot hold an amenity the code
-- has no idea how to render.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE property_amenity (
    property_id uuid         NOT NULL,
    amenity     varchar(255) NOT NULL,

    CONSTRAINT pk_property_amenity  PRIMARY KEY (property_id, amenity),
    CONSTRAINT fk_amenity_property  FOREIGN KEY (property_id) REFERENCES property (id),
    CONSTRAINT ck_property_amenity  CHECK (amenity IN (
        'WIFI', 'POOL', 'GYM', 'SPA', 'PARKING', 'RESTAURANT', 'BAR',
        'AIR_CONDITIONING', 'PET_FRIENDLY', 'AIRPORT_SHUTTLE',
        'BREAKFAST_INCLUDED', 'WHEELCHAIR_ACCESSIBLE'))
);
-- No separate index on property_id: the primary key already leads with it.


-- -------------------------------------------------------------------------------------------------
-- room_type — a category of interchangeable rooms ("Deluxe King"), not a physical room.
--
-- This is the central modelling decision: inventory is a count per (room type, night) rather than an
-- assignment of a named room. Availability becomes integer arithmetic over a date range instead of a
-- matching problem, which is what keeps the concurrency-critical section small.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE room_type (
    id                  uuid           NOT NULL,
    property_id         uuid           NOT NULL,
    name                varchar(255)   NOT NULL,
    max_occupancy       integer        NOT NULL,
    total_rooms         integer        NOT NULL,
    base_price_amount   numeric(38, 2) NOT NULL,
    base_price_currency varchar(255)   NOT NULL,

    CONSTRAINT pk_room_type            PRIMARY KEY (id),
    CONSTRAINT fk_room_type_property   FOREIGN KEY (property_id) REFERENCES property (id),
    CONSTRAINT ck_room_type_occupancy  CHECK (max_occupancy >= 1),
    CONSTRAINT ck_room_type_total      CHECK (total_rooms >= 1),
    CONSTRAINT ck_room_type_price      CHECK (base_price_amount > 0)
);

CREATE INDEX ix_room_type_property_id ON room_type (property_id);


-- -------------------------------------------------------------------------------------------------
-- room_inventory — how many rooms of one type are committed on one night.
--
-- The contended table. Every booking takes `SELECT ... ORDER BY stay_date FOR UPDATE` over its
-- nights here, so the unique constraint below is doing double duty: it is the correctness guarantee
-- that a (room type, night) cannot be duplicated, and it is the index that makes the locking read a
-- range scan rather than a sequential scan under contention.
--
-- `held_rooms <= total_rooms` is the invariant the whole design exists to protect. RoomInventory
-- enforces it in Java; stating it here means even a direct UPDATE cannot oversell a night.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE room_inventory (
    id           uuid    NOT NULL,
    room_type_id uuid    NOT NULL,
    stay_date    date    NOT NULL,
    total_rooms  integer NOT NULL,
    held_rooms   integer NOT NULL,

    CONSTRAINT pk_room_inventory            PRIMARY KEY (id),
    CONSTRAINT uk_inventory_room_type_date  UNIQUE (room_type_id, stay_date),
    CONSTRAINT ck_inventory_not_oversold    CHECK (held_rooms >= 0 AND held_rooms <= total_rooms),
    CONSTRAINT ck_inventory_total_rooms     CHECK (total_rooms >= 1)
);
-- No FK to room_type: inventory is its own aggregate, referenced by identity. That is the same
-- boundary the code draws, and it keeps a lock on one night from touching the property object graph.
-- The unique constraint above already provides the (room_type_id, stay_date) index the range lock
-- and the availability query both need.


-- -------------------------------------------------------------------------------------------------
-- booking — a guest's reservation, with its lifecycle enforced by a state machine.
--
-- Dates are stored as the half-open range [check_in, check_out): the nights occupied are
-- check_in .. check_out - 1, so a guest checking out on the 5th does not collide with one checking
-- in on the 5th.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE booking (
    id              uuid           NOT NULL,
    version         bigint         NOT NULL DEFAULT 0,
    property_id     uuid           NOT NULL,
    room_type_id    uuid           NOT NULL,
    guest_name      varchar(255)   NOT NULL,
    guest_email     varchar(255)   NOT NULL,
    check_in        date           NOT NULL,
    check_out       date           NOT NULL,
    guest_count     integer        NOT NULL,
    room_count      integer        NOT NULL,
    total_amount    numeric(38, 2) NOT NULL,
    total_currency  varchar(255)   NOT NULL,
    status          varchar(255)   NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL,
    hold_expires_at timestamp(6) with time zone NOT NULL,
    confirmed_at    timestamp(6) with time zone,
    cancelled_at    timestamp(6) with time zone,
    refund_amount   numeric(38, 2),
    refund_currency varchar(255),

    CONSTRAINT pk_booking             PRIMARY KEY (id),
    CONSTRAINT ck_booking_status      CHECK (status IN (
        'PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'COMPLETED')),
    CONSTRAINT ck_booking_stay        CHECK (check_out > check_in),
    CONSTRAINT ck_booking_counts      CHECK (guest_count >= 1 AND room_count >= 1),
    CONSTRAINT ck_booking_total       CHECK (total_amount > 0),
    -- A refund only makes sense once cancelled, and can never exceed what was charged.
    CONSTRAINT ck_booking_refund      CHECK (
        refund_amount IS NULL OR (status = 'CANCELLED' AND refund_amount <= total_amount))
);

-- `findByGuestEmailOrderByCreatedAtDesc`. created_at is in the index so the sort is satisfied by the
-- index order rather than a separate sort step.
CREATE INDEX ix_booking_guest_email ON booking (guest_email, created_at DESC);

-- `findLapsedHolds(status, asOf)`, run by the expiry sweeper on a timer. Partial rather than a plain
-- composite: only PENDING_PAYMENT rows can lapse, so confirmed and cancelled bookings — which become
-- the overwhelming majority over time — stay out of the index entirely.
CREATE INDEX ix_booking_lapsed_holds ON booking (hold_expires_at)
    WHERE status = 'PENDING_PAYMENT';


-- -------------------------------------------------------------------------------------------------
-- payment — one attempt to move money for a booking.
--
-- `uk_payment_idempotency_key` is not a nicety; it is the mechanism. Two concurrent requests carrying
-- the same key both try to insert, the database lets exactly one through, and the loser reads back
-- the winner's record. A check-then-insert in application code has a window between the check and
-- the insert; the constraint does not.
-- -------------------------------------------------------------------------------------------------
CREATE TABLE payment (
    id                uuid           NOT NULL,
    booking_id        uuid           NOT NULL,
    idempotency_key   varchar(255)   NOT NULL,
    method            varchar(255)   NOT NULL,
    amount            numeric(38, 2) NOT NULL,
    currency          varchar(255)   NOT NULL,
    status            varchar(255)   NOT NULL,
    gateway_reference varchar(255),
    failure_reason    varchar(255),
    created_at        timestamp(6) with time zone NOT NULL,
    settled_at        timestamp(6) with time zone,
    refunded_amount   numeric(38, 2),
    refunded_currency varchar(255),

    CONSTRAINT pk_payment                  PRIMARY KEY (id),
    CONSTRAINT uk_payment_idempotency_key  UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_status           CHECK (status IN (
        'INITIATED', 'SUCCESSFUL', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payment_method           CHECK (method IN ('CARD', 'UPI', 'WALLET')),
    CONSTRAINT ck_payment_amount           CHECK (amount > 0),
    CONSTRAINT ck_payment_refund_bounded   CHECK (refunded_amount IS NULL OR refunded_amount <= amount)
);

-- `findByBookingIdOrderByCreatedAtDesc` (all attempts) and `findByBookingIdAndStatus` (the settled
-- one, read on every cancellation). status is included so the second query is answered from the
-- index; created_at keeps the first query's ordering free.
CREATE INDEX ix_payment_booking_id ON payment (booking_id, status, created_at DESC);

-- No FK to booking. Payments are retained as a financial record independent of the booking
-- aggregate's lifecycle, matching how the code references them — by id, never by navigation.

COMMIT;

# Low-Level Design — Car Rental System

**Author:** [vinu-varsidh](https://github.com/vinu-varsidh)

LLD reference for the Java car rental in the parent folder. For a from-scratch walkthrough with reasoning, see [Deep Dive](DEEP_DIVE.md).

## Contents

1. [Problem scope](#problem-scope)
2. [Class diagram — domain model](#class-diagram--domain-model)
3. [Class diagram — services, repositories, strategies](#class-diagram--services-repositories-strategies)
4. [Constructor reference](#constructor-reference)
5. [State machines](#state-machines)
6. [Sequence diagrams](#sequence-diagrams)
7. [Availability rule](#availability-rule)
8. [Concurrency model](#concurrency-model)
9. [Pricing](#pricing)
10. [Extension points](#extension-points)
11. [Verification](#verification)

## Problem scope

A customer searches for cars by **category** and **location** for a time window, places a **time-limited hold** on one, **pays**, and the reservation becomes **confirmed**. They can cancel (refund if paid), pick up and return.

In scope: availability without double-booking, hold/expiry, idempotent payments with refund, pluggable pricing, car fleet status.

Out of scope: real payment provider, persistence, auth, late fees, damage/deposit handling, multi-location transfers.

## Class diagram — domain model

Every class shows its **constructor arguments**. Fields marked `-` are private; all domain objects validate their arguments in the constructor and fail fast with `IllegalArgumentException`.

```mermaid
classDiagram
    direction LR

    class Customer {
        -String id
        -String name
        +Customer(String id, String name)
    }

    class Car {
        -String id
        -String registrationNumber
        -String model
        -String category
        -String locationId
        -CarStatus status
        +Car(String id, String registrationNumber, String model, String category, String locationId, CarStatus status)
        +markMaintenance() void
        +markActive() void
        +retire() void
        +isOperational() boolean
    }

    class Reservation {
        -String id
        -String customerId
        -String carId
        -Instant start
        -Instant end
        -Instant holdExpiresAt
        -BigDecimal quotedAmount
        -ReservationStatus status
        +Reservation(String id, String customerId, String carId, Instant start, Instant end, Instant holdExpiresAt, BigDecimal quotedAmount, ReservationStatus status)
        +transition(ReservationStatus expected, ReservationStatus next) boolean
        +isHoldExpired(Instant now) boolean
        +blocksInterval(Instant now) boolean
        +overlaps(Instant start, Instant end) boolean
    }

    class Payment {
        -String id
        -String reservationId
        -String idempotencyKey
        -BigDecimal amount
        -PaymentStatus status
        -String providerTransactionId
        +Payment(String id, String reservationId, String idempotencyKey, BigDecimal amount, PaymentStatus status)
        +markSucceeded(String providerTransactionId) void
        +markFailed() void
        +markRefunded() boolean
    }

    class CarStatus {
        <<enumeration>>
        ACTIVE
        MAINTENANCE
        RETIRED
    }
    class ReservationStatus {
        <<enumeration>>
        PENDING_PAYMENT
        CONFIRMED
        PICKED_UP
        COMPLETED
        CANCELLED
        EXPIRED
    }
    class PaymentStatus {
        <<enumeration>>
        PROCESSING
        SUCCEEDED
        FAILED
        REFUNDED
    }

    Customer "1" --> "0..*" Reservation : makes (by customerId)
    Car "1" <-- "0..*" Reservation : reserves (by carId)
    Reservation "1" <-- "0..1" Payment : pays for (by reservationId)
    Car --> CarStatus
    Reservation --> ReservationStatus
    Payment --> PaymentStatus
```

> Relationships are **by id, not by object reference**. A `Reservation` stores `customerId` / `carId`, not `Customer` / `Car`. This keeps aggregates independent and maps directly to foreign keys.

## Class diagram — services, repositories, strategies

```mermaid
classDiagram
    direction TB

    class BookingService {
        -CarRepository carRepository
        -ReservationRepository reservationRepository
        -PaymentService paymentService
        -PricingStrategy pricingStrategy
        -Duration holdDuration
        -Clock clock
        -Map~String,ReentrantLock~ locksByCarId
        +BookingService(CarRepository carRepository, ReservationRepository reservationRepository, PaymentService paymentService, PricingStrategy pricingStrategy, Duration holdDuration)
        +BookingService(CarRepository, ReservationRepository, PaymentService, PricingStrategy, Duration holdDuration, Clock clock)
        +searchAvailableCars(String category, String locationId, Instant start, Instant end) List~Car~
        +createHold(Customer customer, String category, String locationId, Instant start, Instant end) Reservation
        +confirmBooking(String reservationId, String paymentIdempotencyKey) Reservation
        +cancelBooking(String reservationId) void
        +pickup(String reservationId) void
        +returnCar(String reservationId) void
        +expirePendingHolds() int
    }

    class PaymentService {
        -PaymentRepository paymentRepository
        -PaymentGateway gateway
        +PaymentService(PaymentRepository paymentRepository, PaymentGateway gateway)
        +chargeReservation(Reservation reservation, String idempotencyKey) Payment
        +refund(Payment payment) void
        +findByReservationId(String reservationId) Optional~Payment~
    }

    class PricingStrategy {
        <<interface>>
        +calculatePrice(Car car, Instant start, Instant end) BigDecimal
    }
    class DailyPricingStrategy {
        -Map~String,BigDecimal~ dailyRateByCategory
        +DailyPricingStrategy(Map~String,BigDecimal~ dailyRateByCategory)
        +calculatePrice(Car car, Instant start, Instant end) BigDecimal
    }

    class CarRepository {
        <<interface>>
        +save(Car car) void
        +findById(String carId) Optional~Car~
        +findCandidates(String category, String locationId) List~Car~
        +findAll() List~Car~
    }
    class ReservationRepository {
        <<interface>>
        +save(Reservation reservation) void
        +findById(String reservationId) Optional~Reservation~
        +findByCarId(String carId) List~Reservation~
        +hasOverlappingBlockingReservation(String carId, Instant start, Instant end, Instant now) boolean
        +findExpiredHolds(Instant now) List~Reservation~
    }
    class PaymentRepository {
        <<interface>>
        +findByIdempotencyKey(String key) Optional~Payment~
        +findByReservationId(String reservationId) Optional~Payment~
        +saveIfAbsent(Payment candidate) Payment
    }

    class InMemoryCarRepository {
        +InMemoryCarRepository()
    }
    class InMemoryReservationRepository {
        +InMemoryReservationRepository()
    }
    class InMemoryPaymentRepository {
        +InMemoryPaymentRepository()
    }

    class PaymentGateway {
        <<interface>>
        +charge(String idempotencyKey, BigDecimal amount) GatewayResult
        +refund(String providerTransactionId, String refundIdempotencyKey) void
    }
    class DemoPaymentGateway {
        +DemoPaymentGateway()
    }
    class GatewayResult {
        <<record>>
        +GatewayResult(boolean succeeded, String transactionId)
    }

    BookingService --> CarRepository
    BookingService --> ReservationRepository
    BookingService --> PaymentService
    BookingService --> PricingStrategy
    PaymentService --> PaymentRepository
    PaymentService --> PaymentGateway

    PricingStrategy <|.. DailyPricingStrategy
    CarRepository <|.. InMemoryCarRepository
    ReservationRepository <|.. InMemoryReservationRepository
    PaymentRepository <|.. InMemoryPaymentRepository
    PaymentGateway <|.. DemoPaymentGateway
    PaymentGateway ..> GatewayResult : returns
```

### Exceptions

```mermaid
classDiagram
    direction LR
    class RuntimeException
    class CarRentalException {
        +CarRentalException(String message)
    }
    class NoAvailableCarException {
        +NoAvailableCarException(String message)
    }
    class ReservationNotFoundException {
        +ReservationNotFoundException(String reservationId)
    }
    class InvalidReservationStateException {
        +InvalidReservationStateException(String message)
    }
    class HoldExpiredException {
        +HoldExpiredException(String reservationId)
    }
    class PaymentFailedException {
        +PaymentFailedException(String message)
    }
    RuntimeException <|-- CarRentalException
    CarRentalException <|-- NoAvailableCarException
    CarRentalException <|-- ReservationNotFoundException
    CarRentalException <|-- InvalidReservationStateException
    CarRentalException <|-- HoldExpiredException
    CarRentalException <|-- PaymentFailedException
```

Bad *input* (null, blank, `start >= end`, start in the past, duplicate ids, idempotency-key misuse) throws `IllegalArgumentException`. Bad *business state* throws a `CarRentalException` subtype.

## Constructor reference

| Class | Constructor arguments | Validation |
|---|---|---|
| `Customer` | `String id, String name` | both non-blank |
| `Car` | `String id, String registrationNumber, String model, String category, String locationId, CarStatus status` | strings non-blank, status non-null |
| `Reservation` | `String id, String customerId, String carId, Instant start, Instant end, Instant holdExpiresAt, BigDecimal quotedAmount, ReservationStatus status` | ids non-blank, `start < end`, amount ≥ 0 |
| `Payment` | `String id, String reservationId, String idempotencyKey, BigDecimal amount, PaymentStatus status` | ids/key non-blank, amount non-null |
| `BookingService` | `CarRepository, ReservationRepository, PaymentService, PricingStrategy, Duration holdDuration [, Clock clock]` | all non-null, hold duration > 0; clock defaults to `Clock.systemUTC()` |
| `PaymentService` | `PaymentRepository, PaymentGateway` | non-null |
| `DailyPricingStrategy` | `Map<String, BigDecimal> dailyRateByCategory` | defensively copied (`Map.copyOf`) |
| `GatewayResult` | `boolean succeeded, String transactionId` | none (record) |
| `InMemory*Repository`, `DemoPaymentGateway` | *(none)* | — |

## State machines

### Reservation

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT : createHold
    PENDING_PAYMENT --> CONFIRMED : confirmBooking (payment ok, hold valid)
    PENDING_PAYMENT --> CANCELLED : cancelBooking / payment failed
    PENDING_PAYMENT --> EXPIRED : hold lapsed (sweeper or late pay attempt)
    CONFIRMED --> CANCELLED : cancelBooking (refund)
    CONFIRMED --> PICKED_UP : pickup (now >= start)
    PICKED_UP --> COMPLETED : returnCar
    CANCELLED --> [*]
    EXPIRED --> [*]
    COMPLETED --> [*]
```

| Status | Blocks the car's calendar? |
|---|---|
| `PENDING_PAYMENT` | **Yes, while `holdExpiresAt` is in the future** |
| `CONFIRMED`, `PICKED_UP` | Yes |
| `COMPLETED`, `CANCELLED`, `EXPIRED` | No |

All transitions go through `Reservation.transition(expected, next)`, a compare-and-set: it succeeds only if the status is still what the caller observed.

### Payment

```mermaid
stateDiagram-v2
    [*] --> PROCESSING : saveIfAbsent
    PROCESSING --> SUCCEEDED : gateway charge ok
    PROCESSING --> FAILED : gateway charge declined
    SUCCEEDED --> REFUNDED : cancel / late confirmation
    FAILED --> [*]
    REFUNDED --> [*]
```

### Car

```mermaid
stateDiagram-v2
    [*] --> ACTIVE
    ACTIVE --> MAINTENANCE : markMaintenance
    MAINTENANCE --> ACTIVE : markActive
    ACTIVE --> RETIRED : retire
    MAINTENANCE --> RETIRED : retire
    RETIRED --> [*]
```

`CarStatus` is **fleet** status. Whether a car is *booked* is never stored on `Car`; it is derived from the reservations.

## Sequence diagrams

### Search → hold → pay → confirm

```mermaid
sequenceDiagram
    actor C as Customer
    participant B as BookingService
    participant CR as CarRepository
    participant RR as ReservationRepository
    participant P as PaymentService
    participant G as PaymentGateway

    C->>B: searchAvailableCars(category, location, start, end)
    B->>CR: findCandidates(category, location)
    loop each candidate (under its car lock)
        B->>RR: hasOverlappingBlockingReservation(...)
    end
    B-->>C: free cars (advisory)

    C->>B: createHold(customer, category, location, start, end)
    loop candidates in id order
        B->>B: lock(car)
        B->>RR: hasOverlappingBlockingReservation(...)
        alt free
            B->>RR: save(PENDING_PAYMENT, holdExpiresAt = now + holdDuration)
            B->>B: unlock, return hold
        else busy
            B->>B: unlock, try next car
        end
    end
    B-->>C: Reservation (or NoAvailableCarException)

    C->>B: confirmBooking(reservationId, idempotencyKey)
    Note over B: no lock held during payment I/O
    B->>P: chargeReservation(reservation, key)
    P->>G: charge(key, amount)
    G-->>P: GatewayResult
    P-->>B: Payment
    B->>B: lock(car): still PENDING and hold valid?
    alt yes
        B->>B: transition PENDING_PAYMENT to CONFIRMED
        B-->>C: CONFIRMED
    else no (expired or cancelled while paying)
        B->>P: refund(payment)
        B-->>C: InvalidReservationStateException
    end
```

### Cancel

```mermaid
sequenceDiagram
    actor C as Customer
    participant B as BookingService
    participant P as PaymentService
    participant G as PaymentGateway

    C->>B: cancelBooking(reservationId)
    B->>B: lock(car): transition current to CANCELLED
    Note over B: rejected if PICKED_UP or COMPLETED, no-op if already CANCELLED or EXPIRED
    B->>P: findByReservationId
    alt payment SUCCEEDED
        B->>P: refund(payment)
        P->>G: refund(txnId, "refund-" + paymentId)
        P->>P: markRefunded
    end
    B-->>C: done
```

## Availability rule

Rentals are **half-open intervals `[start, end)`**. Two rentals overlap iff

```
a.start < b.end  AND  b.start < a.end
```

so a car returned at 10:00 can be picked up by the next customer at 10:00.

A car is available for a requested window iff it is `ACTIVE` and has **no reservation** that both *blocks* (see the [table](#reservation)) and *overlaps* the window.

Because an expired hold simply stops blocking, **no background job is required for correctness**. `expirePendingHolds()` only tidies statuses.

## Concurrency model

| Concern | Mechanism |
|---|---|
| Two customers race for the same car/window | Per-car `ReentrantLock` makes *check overlap + save hold* atomic |
| Stale search results | Search is advisory; `createHold` re-checks under the lock |
| Status transitions racing (pay vs cancel vs expiry) | `Reservation.transition` is a synchronized compare-and-set, executed under the car lock |
| External payment call latency | Charge runs **outside** any lock; the result is reconciled under the lock afterwards |
| Payment succeeded but hold lapsed / cancelled mid-flight | Compensating **refund** |
| Retried / duplicate pay requests | Idempotency key on `Payment`; `saveIfAbsent` is atomic; repeat confirm returns the already-confirmed reservation |
| Double refund | Refund key derived from payment id; `markRefunded` is idempotent |
| Payment indexes diverging | `InMemoryPaymentRepository` updates both maps under one monitor |

**Lock order:** car lock → reservation monitor / payment monitor. Nothing acquires a car lock while holding a monitor, so there is no cycle.

**Limitation:** the locks are in-JVM. Multiple app instances need a database-level guarantee (see [Extension points](#extension-points)).

## Pricing

`DailyPricingStrategy` bills **each started 24-hour block**, minimum one day, `HALF_UP` to 2 decimals.

| Rental length | Billed days |
|---|---|
| 1 hour | 1 |
| 24 hours | 1 |
| 25 hours | 2 |
| 48 hours | 2 |

The price is **quoted once at hold time** and stored on the reservation (`quotedAmount`), so later rate changes never alter an existing booking and the amount charged always equals the amount shown.

## Extension points

| Need | Where |
|---|---|
| Hourly / weekend / surge / tax pricing | New `PricingStrategy` |
| Real PSP (Stripe, Razorpay) | New `PaymentGateway`; must honor idempotency keys |
| Database persistence | New `*Repository` implementations; replace the car lock with a DB constraint |
| Multi-instance safety | Postgres: `EXCLUDE USING gist (car_id WITH =, tstzrange(start_ts, end_ts) WITH &&) WHERE (status IN (...))` |
| Hold sweeper | `ScheduledExecutorService` calling `expirePendingHolds()` |
| Notifications | Domain events published on each transition |
| Cancellation policy | Fee/refund calculator consulted in `cancelBooking` |

## Verification

`CarRentalVerification` runs 88 checks, including a 32-thread race for one car and a 16-thread duplicate-confirm race. Time-dependent behavior (hold expiry, pickup time) uses an injected `MutableClock`, so nothing sleeps. See the [README](../README.md#run-the-verification) for how to run.

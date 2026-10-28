# Car Rental System (Java)

**Author:** [vinu-varsidh](https://github.com/vinu-varsidh) — design and implementation.

A small, thread-aware car rental domain model. Customers search for cars by category and location for a time window, place a **time-limited hold**, **pay**, and the reservation is **confirmed**. It prevents double-booking, handles hold expiry, makes payments **idempotent**, and **refunds** automatically when money is taken but the booking can't be confirmed.

**Requirements:** Java 17+ (records, switch expressions, `Map.copyOf`). No third-party dependencies.

> **New to the design?** Read [LLD/DEEP_DIVE.md](LLD/DEEP_DIVE.md) for the full walkthrough. UML class diagrams (with constructor arguments), state machines and sequence diagrams are in [LLD/README.md](LLD/README.md).

## Contents

- [Quick start](#quick-start)
- [Run the verification](#run-the-verification)
- [Project layout](#project-layout)
- [High-level flow](#high-level-flow)
- [Using the API](#using-the-api)
- [Code reference](#code-reference)
- [Rules and behavior](#rules-and-behavior)
- [Design notes](#design-notes)
- [Troubleshooting](#troubleshooting)
- [License](#license)

## Quick start

### 1. Check Java

```bash
java -version      # must report 17 or newer
```

### 2. Build and run the demo

With Maven:

```bash
mvn compile exec:java
```

Without Maven (plain `javac`):

```bash
mkdir -p target/classes
find src/main/java -name '*.java' | xargs javac --release 17 -d target/classes
java -cp target/classes com.carrental.CarRentalDemo
```

### 3. Expected output

Ids and timestamps vary; the shape is:

```
1. Search HATCHBACK @ CHENNAI-01 for 2 days
   CAR-1 (Swift, HATCHBACK, CHENNAI-01)
2. Create hold (5 min to pay)
   Reservation{id='…', carId='CAR-1', start=…, end=…, status=PENDING_PAYMENT, amount=3000.00}
3. Search again: the held car is no longer offered
   available = 0
4. Pay and confirm
   Reservation{id='…', carId='CAR-1', …, status=CONFIRMED, amount=3000.00}
5. Cancel (refund issued)
  [gateway] refund issued for TXN-…
   status = CANCELLED, payment = REFUNDED
6. Reservations for CAR-1
   Reservation{id='…', carId='CAR-1', …, status=CANCELLED, amount=3000.00}
```

What each step shows:

| Step | Demonstrates |
|---|---|
| 1 | Search by category and location (advisory) |
| 2 | Hold: price quoted (2 days × ₹1500 = ₹3000.00), 5 minutes to pay |
| 3 | A live hold hides the car from search |
| 4 | Payment then confirmation |
| 5 | Cancelling a paid booking refunds it |
| 6 | Reservation history stays on record |

## Run the verification

The project has no test framework. `CarRentalVerification` is a self-contained runner with 88 checks, in the same style as the sibling projects.

```bash
# (re)compile if you haven't already
mkdir -p target/classes
find src/main/java -name '*.java' | xargs javac --release 17 -d target/classes

java -cp target/classes com.carrental.CarRentalVerification
```

Output is one `PASS` / `FAIL` line per check and a summary:

```
Verification: 88 passed, 0 failed
```

The process exits with code `1` if any check fails, so it works in CI. To see only problems:

```bash
java -cp target/classes com.carrental.CarRentalVerification | grep -v PASS
```

### What is covered

| Area | Checks |
|---|---|
| Pricing | 1 h → 1 day, 24 h → 1 day, 25 h → 2 days, unknown category rejected |
| Search | filters by category (case-insensitive), location, car status |
| Overlap | overlapping / contained / enclosing windows rejected; **back-to-back allowed** |
| Assignment | falls through to the next car when the first is busy |
| Hold expiry | live hold blocks; expired hold frees the car; late payment → `HoldExpiredException`, no charge |
| Payment failure | reservation cancelled, car freed, no refund |
| Idempotency | repeat confirm charges once; key reuse across reservations rejected |
| Cancel | refund on paid booking; second cancel is a no-op; pending hold cancels without refund |
| Lifecycle | pickup before start rejected; pickup, return; cancel after pickup rejected |
| Compensation | hold expires mid-payment → refunded; cancelled mid-payment → refunded |
| Sweeper | `expirePendingHolds()` expires only unpaid holds |
| Validation | bad intervals, past start, blank ids, duplicate car, bad hold duration, negative quote |
| Car rules | maintenance, reactivation, retired is terminal |
| **Concurrency** | 32 threads race for one car → exactly 1 wins; 16 concurrent confirms with one key → all `CONFIRMED`, no refund |

Time-dependent checks use an injected controllable `Clock`, so nothing sleeps and results are deterministic.

## Project layout

```
car-rental/
├── pom.xml
├── README.md
├── LICENSE
├── LLD/
│   ├── README.md          UML (class, state, sequence), constructor reference, concurrency
│   └── DEEP_DIVE.md       Full walkthrough, decisions, flaws, suggestions
└── src/main/java/com/carrental/
    ├── CarRentalDemo.java
    ├── CarRentalVerification.java
    ├── enums/        CarStatus, ReservationStatus, PaymentStatus
    ├── exception/    CarRentalException + NoAvailableCar, ReservationNotFound,
    │                 InvalidReservationState, HoldExpired, PaymentFailed
    ├── model/        Customer, Car, Reservation, Payment
    ├── payment/      PaymentGateway, GatewayResult, DemoPaymentGateway, PaymentService
    ├── repository/   Car/Reservation/Payment repository interfaces + InMemory* impls
    ├── service/      BookingService
    ├── strategy/     PricingStrategy, DailyPricingStrategy
    └── util/         Validation
```

## High-level flow

```
 search ──► createHold ──► confirmBooking ──► pickup ──► returnCar
 (advisory)  PENDING_PAYMENT   CONFIRMED       PICKED_UP   COMPLETED
                │   │              │
                │   └─ hold lapses ─► EXPIRED
                └──── payment fails / cancel ─► CANCELLED
                                     │
                       CONFIRMED ────┘ cancel (refund)
```

Step by step:

1. **Search**: `searchAvailableCars` lists active cars of the category at the location with no blocking reservation overlapping the window. Results are advisory.
2. **Hold**: `createHold` re-checks under a per-car lock, quotes the price, and saves a `PENDING_PAYMENT` reservation that expires after the hold duration.
3. **Pay**: `confirmBooking` charges via the gateway (outside any lock) using your idempotency key.
4. **Confirm**: under the lock, if the hold is still valid, the status becomes `CONFIRMED`. Otherwise the payment is refunded and an exception is thrown.
5. **Cancel**: allowed until pickup; refunds a succeeded payment.
6. **Pickup / return**: at or after the start time; then complete.

Full diagrams: [LLD/README.md](LLD/README.md#sequence-diagrams).

## Using the API

```java
// 1. Wire dependencies
CarRepository cars = new InMemoryCarRepository();
ReservationRepository reservations = new InMemoryReservationRepository();
PaymentService payments = new PaymentService(
        new InMemoryPaymentRepository(), new DemoPaymentGateway());
PricingStrategy pricing = new DailyPricingStrategy(Map.of(
        "HATCHBACK", new BigDecimal("1500.00"),
        "SEDAN",     new BigDecimal("2200.00")));

BookingService booking = new BookingService(
        cars, reservations, payments, pricing, Duration.ofMinutes(5));

// 2. Add fleet
cars.save(new Car("CAR-1", "TN01AB1234", "Swift", "HATCHBACK", "CHENNAI-01", CarStatus.ACTIVE));

// 3. Book
Customer customer = new Customer("CUS-1", "Vinu");
Instant start = Instant.now().plus(Duration.ofHours(1));
Instant end   = start.plus(Duration.ofDays(2));

List<Car> free  = booking.searchAvailableCars("HATCHBACK", "CHENNAI-01", start, end);
Reservation hold = booking.createHold(customer, "HATCHBACK", "CHENNAI-01", start, end);
Reservation ok   = booking.confirmBooking(hold.getId(), "pay-" + hold.getId());

// 4. Later
booking.pickup(ok.getId());      // at or after start
booking.returnCar(ok.getId());
// or: booking.cancelBooking(ok.getId());
```

**Idempotency key:** pick a value that is stable for one *payment intent* (for example `"pay-" + reservationId`). Retrying with the same key is always safe. Using the same key on a different reservation is rejected.

### Testing time-dependent code

```java
BookingService booking = new BookingService(
        cars, reservations, payments, pricing, Duration.ofMinutes(5), myControllableClock);
```

## Code reference

### Enums (`com.carrental.enums`)

| Enum | Values |
|---|---|
| `CarStatus` | `ACTIVE`, `MAINTENANCE`, `RETIRED` |
| `ReservationStatus` | `PENDING_PAYMENT`, `CONFIRMED`, `PICKED_UP`, `COMPLETED`, `CANCELLED`, `EXPIRED` |
| `PaymentStatus` | `PROCESSING`, `SUCCEEDED`, `FAILED`, `REFUNDED` |

### Models (`com.carrental.model`)

| Class | Constructor arguments | Notable behavior |
|---|---|---|
| `Customer` | `String id, String name` | Immutable |
| `Car` | `String id, String registrationNumber, String model, String category, String locationId, CarStatus status` | `markMaintenance()`, `markActive()`, `retire()`, `isOperational()`; `RETIRED` is terminal |
| `Reservation` | `String id, String customerId, String carId, Instant start, Instant end, Instant holdExpiresAt, BigDecimal quotedAmount, ReservationStatus status` | `transition(expected, next)` (CAS), `isHoldExpired(now)`, `blocksInterval(now)`, `overlaps(s, e)` |
| `Payment` | `String id, String reservationId, String idempotencyKey, BigDecimal amount, PaymentStatus status` | `markSucceeded(txnId)`, `markFailed()`, `markRefunded()` |

### Services

| Class | Constructor arguments | Public methods |
|---|---|---|
| `BookingService` | `CarRepository, ReservationRepository, PaymentService, PricingStrategy, Duration holdDuration [, Clock clock]` | `searchAvailableCars`, `createHold`, `confirmBooking`, `cancelBooking`, `pickup`, `returnCar`, `expirePendingHolds` |
| `PaymentService` | `PaymentRepository, PaymentGateway` | `chargeReservation`, `refund`, `findByReservationId` |

### Strategy

| Type | Notes |
|---|---|
| `PricingStrategy` | `BigDecimal calculatePrice(Car car, Instant start, Instant end)` |
| `DailyPricingStrategy(Map<String, BigDecimal> dailyRateByCategory)` | Each started 24 h block billed, minimum 1 day, 2 decimals `HALF_UP` |

### Ports and in-memory adapters

| Interface | Implementation | Key methods |
|---|---|---|
| `CarRepository` | `InMemoryCarRepository` | `save`, `findById`, `findCandidates(category, location)`, `findAll` |
| `ReservationRepository` | `InMemoryReservationRepository` | `save`, `findById`, `findByCarId`, `hasOverlappingBlockingReservation`, `findExpiredHolds` |
| `PaymentRepository` | `InMemoryPaymentRepository` | `findByIdempotencyKey`, `findByReservationId`, `saveIfAbsent` |
| `PaymentGateway` | `DemoPaymentGateway` | `charge(key, amount)` → `GatewayResult`, `refund(txnId, refundKey)` |

### Exceptions (`com.carrental.exception`)

| Exception | Thrown when |
|---|---|
| `NoAvailableCarException` | No car of the category is free for the window |
| `ReservationNotFoundException` | Unknown reservation id |
| `InvalidReservationStateException` | Operation illegal in the current state (e.g. pickup before start, cancel after pickup, payment succeeded but confirmation lost the race) |
| `HoldExpiredException` | Paying after the hold lapsed |
| `PaymentFailedException` | Gateway declined the charge |
| `IllegalArgumentException` | Bad input: blank/null values, `start >= end`, start in the past, duplicate ids, idempotency-key misuse |

## Rules and behavior

| Rule | Detail |
|---|---|
| Intervals | Half-open `[start, end)`. A rental ending at 10:00 and one starting at 10:00 do **not** overlap |
| Blocking | `PENDING_PAYMENT` (until `holdExpiresAt`), `CONFIRMED`, `PICKED_UP` block. `COMPLETED`, `CANCELLED`, `EXPIRED` don't |
| Assignment | Lowest car id first among free cars of the category/location |
| Price | Quoted at hold time and stored; charged amount always equals the quote |
| Hold | Expired holds stop blocking immediately; no background job is required |
| Failed payment | Reservation is cancelled and the car freed at once |
| Paid but unconfirmable | Hold lapsed or cancelled mid-payment → automatic refund + `InvalidReservationStateException` |
| Cancel | Not allowed after pickup. Idempotent. Refunds a succeeded payment |
| Pickup | Allowed at/after `start`, only from `CONFIRMED` |

## Design notes

- **Per-car locking** makes *check overlap + save hold* atomic; payment I/O never runs under a lock.
- **Compare-and-set transitions** (`Reservation.transition`) keep concurrent pay / cancel / expire from corrupting state.
- **Idempotency** lives in `PaymentRepository.saveIfAbsent` and in the gateway key; refunds use a key derived from the payment id.
- **Injected `Clock`** makes expiry and pickup rules testable without sleeping.
- **Scope:** single JVM, in-memory. Across multiple instances you need database-level enforcement. See [Known flaws](LLD/DEEP_DIVE.md#7-known-flaws-explained-in-depth) and [Suggestions](LLD/DEEP_DIVE.md#10-suggestions-for-next-steps).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `mvn: command not found` | Use the `javac` commands above; Maven is optional |
| `invalid target release: 17` / `release version 17 not supported` | Install JDK 17+ and check `java -version` / `javac -version` |
| `NoAvailableCarException` | Wrong category/location string, car not `ACTIVE`, or a blocking reservation overlaps |
| `IllegalArgumentException: Rental start must not be in the past` | Use a future `start` (or a `Clock` set earlier) |
| `HoldExpiredException` | Hold duration elapsed before `confirmBooking`; create a new hold |
| `IllegalArgumentException: Idempotency key already belongs to another reservation` | Use a distinct key per reservation |
| Class not found at runtime | Run from the project root with `-cp target/classes` |

## License

MIT. See [LICENSE](LICENSE).

# Car Rental — Deep Dive

**Author:** [vinu-varsidh](https://github.com/vinu-varsidh)

A from-scratch walkthrough of the design: the problem, the vocabulary, how a booking flows, why each class looks the way it does, and where it breaks. Read it top to bottom once; later use the table of contents as a reference. The compact diagrams live in [LLD/README.md](README.md).

## Table of contents

1. [The real problem](#1-the-real-problem)
2. [Vocabulary you need first](#2-vocabulary-you-need-first)
3. [The big picture](#3-the-big-picture)
4. [End-to-end walkthroughs](#4-end-to-end-walkthroughs)
5. [Class-by-class: decisions and invariants](#5-class-by-class-decisions-and-invariants)
6. [Cross-cutting decisions](#6-cross-cutting-decisions)
7. [Known flaws, explained in depth](#7-known-flaws-explained-in-depth)
8. [How the pieces would change](#8-how-the-pieces-would-change)
9. [Improvements made over the first draft](#9-improvements-made-over-the-first-draft)
10. [Suggestions for next steps](#10-suggestions-for-next-steps)
11. [Self-test](#11-self-test)

---

## 1. The real problem

"Rent a car" sounds like CRUD. It is not. Four hard problems hide inside it:

1. **Time-based availability.** A car is not "available" or "unavailable". It is available *for a window*. Car X can be free next Monday and booked this Friday. So availability is a question about **intervals**, not a flag on the car.
2. **Double-booking.** Two customers click "Book" for the same car at the same moment. Exactly one may win. Checking "is it free?" and then "save booking" as two separate steps is a classic **check-then-act race**.
3. **Paying takes time and can fail.** Payment is a slow external call. If we reserve the car only *after* payment, two people can pay for the same car. If we reserve it *before* payment forever, an abandoned checkout blocks the car. The answer is a **temporary hold**.
4. **Retries and partial failure.** Networks drop; users double-click; the payment may succeed while our own server times out. Charging twice, or taking money without a booking, are both unacceptable.

The design below is essentially four answers to those four questions:

| Problem | Answer |
|---|---|
| Availability is per-window | Derive it from reservations on half-open intervals `[start, end)` |
| Double-booking | Per-car lock around *check overlap + save hold* |
| Slow, fallible payment | Hold with expiry, pay outside the lock, confirm after |
| Retries / partial failure | Idempotency keys + compensating refund |

## 2. Vocabulary you need first

| Term | Meaning here |
|---|---|
| **Category** | A class of car (`HATCHBACK`, `SEDAN`, `SUV`). Customers book a *category*, not a specific car. |
| **Hold** | A `PENDING_PAYMENT` reservation that reserves the car for a few minutes while the customer pays. |
| **Half-open interval** | `[start, end)`: includes `start`, excludes `end`. Lets back-to-back rentals touch without overlapping. |
| **Overlap** | `a.start < b.end && b.start < a.end`. |
| **Blocking reservation** | One that currently prevents others from booking the car. |
| **Idempotency key** | A client-chosen string. Repeating a request with the same key must have the same effect as doing it once. |
| **Compensating action** | Undoing a side effect we can't roll back transactionally (here: refunding a charge). |
| **Check-then-act race** | Two threads both pass the "is free?" check, then both write. Fixed by making check+write atomic. |
| **Compare-and-set (CAS)** | "Change X to B *only if* it is still A." Used for status transitions. |
| **Advisory vs authoritative** | Search results are advisory (may be stale the next millisecond); the check inside `createHold` is authoritative. |
| **Fleet status vs booking status** | `CarStatus` (active/maintenance/retired) says whether the car *can* be rented. Whether it *is* booked is derived from reservations. |

## 3. The big picture

### 3.1 Layers

```
 ┌──────────────────────────────────────────────────────────────┐
 │ CarRentalDemo / CarRentalVerification        (callers)       │
 ├──────────────────────────────────────────────────────────────┤
 │ BookingService      orchestration, locking, state transitions │
 ├───────────────────────────────┬──────────────────────────────┤
 │ PaymentService                │ PricingStrategy              │
 │   idempotency, refund         │   DailyPricingStrategy       │
 ├───────────────┬───────────────┼──────────────────────────────┤
 │ PaymentGateway│ *Repository   │ model: Car, Customer,        │
 │ (port)        │ (ports)       │ Reservation, Payment         │
 └───────────────┴───────────────┴──────────────────────────────┘
```

Dependencies point **downward and to interfaces**. `BookingService` never mentions `InMemoryCarRepository`; the demo wires concrete classes in. This is plain constructor-based dependency injection, with no framework.

### 3.2 Who talks to whom

- `BookingService` is the only entry point. It owns the *workflow* and the *locks*.
- `PaymentService` owns *payment correctness* (idempotency, refunds). It knows nothing about cars or locks.
- Repositories are dumb storage with a few domain queries (`findCandidates`, `hasOverlappingBlockingReservation`).
- Model classes own their **own invariants** (validation, legal transitions) but not workflows.

### 3.3 Wiring

```java
CarRepository cars = new InMemoryCarRepository();
ReservationRepository reservations = new InMemoryReservationRepository();
PaymentService payments = new PaymentService(new InMemoryPaymentRepository(), new DemoPaymentGateway());
PricingStrategy pricing = new DailyPricingStrategy(Map.of("HATCHBACK", new BigDecimal("1500.00")));

BookingService booking = new BookingService(cars, reservations, payments, pricing, Duration.ofMinutes(5));
```

The second constructor additionally takes a `Clock`; tests pass a controllable one.

### 3.4 Package map

| Package | Contents |
|---|---|
| `com.carrental` | `CarRentalDemo`, `CarRentalVerification` |
| `.enums` | `CarStatus`, `ReservationStatus`, `PaymentStatus` |
| `.model` | `Customer`, `Car`, `Reservation`, `Payment` |
| `.strategy` | `PricingStrategy`, `DailyPricingStrategy` |
| `.repository` | 3 interfaces + 3 `InMemory*` implementations |
| `.payment` | `PaymentGateway`, `GatewayResult`, `DemoPaymentGateway`, `PaymentService` |
| `.service` | `BookingService` |
| `.exception` | `CarRentalException` + 5 subtypes |
| `.util` | `Validation` |

## 4. End-to-end walkthroughs

All times use a fixed clock `T0 = 2030-01-01T10:00Z`, hold duration 5 minutes, one hatchback `H1` at `CHENNAI-01` at ₹1500/day.

### 4.1 The overlap rule, concretely

Existing booking on `H1`: **[10h, 20h)** (hours after T0).

| Requested window | Overlap? | Why |
|---|---|---|
| [15h, 25h) | yes | `10<25 && 15<20` |
| [12h, 14h) | yes | contained |
| [5h, 30h) | yes | encloses |
| [20h, 30h) | **no** | `10<30` but `20<20` is false: touching at 20h is fine |
| [1h, 10h) | **no** | `10<10` false |

### 4.2 Happy path: hold, pay, confirm

1. **`searchAvailableCars("HATCHBACK", "CHENNAI-01", T0+1h, T0+49h)`**
   `CarRepository.findCandidates` returns operational hatchbacks at that location → `[H1]`. For each, under its car lock, `hasOverlappingBlockingReservation` is checked → none. Returns `[H1]`. *Advisory only.*
2. **`createHold(alice, "HATCHBACK", "CHENNAI-01", T0+1h, T0+49h)`**
   - Validates `start < end` and `start` is not in the past.
   - For `H1`: lock, re-check operational + no overlap (**authoritative**), price = 48 h → 2 days → ₹3000.00, build `Reservation(PENDING_PAYMENT, holdExpiresAt = T0+5m)`, save, unlock.
3. **Search again** → `[]`: a live hold blocks.
4. **`confirmBooking(resId, "pay-1")`**
   - Status is `PENDING_PAYMENT`, hold not expired.
   - `PaymentService.chargeReservation` → stores `Payment(PROCESSING)` atomically, calls the gateway **outside any lock**, marks `SUCCEEDED`.
   - Lock the car, re-check "still `PENDING_PAYMENT`, hold still valid, car still operational", CAS `PENDING_PAYMENT → CONFIRMED`, unlock.
5. Search again → still `[]`: `CONFIRMED` blocks.

### 4.3 Two customers, one car

Alice and Bob both call `createHold` for the same window at the same instant.

```
Alice: lock(H1) ──check free──save hold──unlock
Bob:       wait ......................lock(H1) ──check──BUSY──unlock → next candidate → none
                                                                       → NoAvailableCarException
```

Without the lock, both checks would run before either save and both would succeed. The verification suite fires **32 threads** at one car and asserts exactly one win.

### 4.4 Hold expiry

- At `T0+4m59s` Bob's request for the same window is rejected: Alice's hold is live.
- At `T0+5m` the hold has expired (`holdExpiresAt` is **not after** `now`). It now **stops blocking**: search shows the car again and Bob can hold it.
- If Alice tries to pay now: `confirmBooking` sees `isHoldExpired`, marks the reservation `EXPIRED`, throws `HoldExpiredException`. **No charge is attempted.**

No sweeper had to run. That is the point of deriving "blocking" from `holdExpiresAt` rather than from a status that a background job must flip.

### 4.5 Payment declines

`confirmBooking` → gateway returns failure → `Payment` becomes `FAILED` → reservation is `CANCELLED` (or `EXPIRED` if the hold had lapsed anyway) so the car is **immediately** free → `PaymentFailedException`. No refund, because nothing was charged.

### 4.6 Money taken, but we can't confirm (the interesting one)

Payment is slow. During the charge the hold lapses (or the user cancels in another tab).

```
t=0     confirmBooking starts, hold valid, no lock held
t=0..6m gateway.charge(...) is in flight  (hold expires at 5m)
t=6m    charge returns SUCCEEDED
t=6m    lock(car): hold expired → cannot CAS to CONFIRMED
t=6m    paymentService.refund(payment)  ← compensating action
        throw InvalidReservationStateException("…refund issued")
```

Charging inside the lock would avoid this but would freeze every other booking for that car (and, with a real PSP, for seconds). The trade-off chosen: **short critical sections + compensation**. Two tests simulate this by letting the gateway advance the clock / cancel the booking mid-charge.

### 4.7 Retry and double-click

Same `confirmBooking(resId, "k")` twice (sequentially or concurrently):

- Second call: status is already `CONFIRMED` → return it. Gateway not called again.
- Concurrent: both pass the first checks, both call `chargeReservation`. `saveIfAbsent` hands both the **same** `Payment`; the gateway is idempotent on the key; both reach the final lock section. The first CASes to `CONFIRMED`; the second sees `CONFIRMED` and returns it. **It must not refund**, because that would refund the winner's payment. See [§9](#9-improvements-made-over-the-first-draft).

Reusing key `"k"` for a *different* reservation → `IllegalArgumentException`.

### 4.8 Cancel, pickup, return

| Action | Allowed from | Result | Side effect |
|---|---|---|---|
| `cancelBooking` | `PENDING_PAYMENT`, `CONFIRMED` | `CANCELLED` | refund if payment `SUCCEEDED` |
| `cancelBooking` | `CANCELLED`, `EXPIRED` | unchanged | none (idempotent no-op) |
| `cancelBooking` | `PICKED_UP`, `COMPLETED` | rejected | `InvalidReservationStateException` |
| `pickup` | `CONFIRMED` and `now >= start` | `PICKED_UP` | none |
| `returnCar` | `PICKED_UP` | `COMPLETED` | none |

Cancel does the **state change first, then the refund**. If we crash in between, the booking is cancelled but the refund is pending: a recoverable state (a reconciliation job finds `CANCELLED` + `SUCCEEDED`). The reverse order could refund money for a booking that stays active.

## 5. Class-by-class: decisions and invariants

### 5.1 `Customer`
Immutable, two validated strings. Deliberately thin: identity and display name. Auth, contact details and licence verification would live elsewhere.

### 5.2 `Car`
- **Immutable identity** (`id`, `registrationNumber`, `model`, `category`, `locationId`), **mutable** `status` (`volatile`, since it is read under different locks than it is written).
- `isOperational()` is the single definition of "can be rented".
- `RETIRED` is **terminal**: `markActive()` and `markMaintenance()` throw.
- **Not stored on `Car`:** any "booked" flag or reservation list. Storing it would duplicate truth and desynchronise.
- Maintenance does **not** cancel existing reservations. See [§7](#7-known-flaws-explained-in-depth).

### 5.3 `Reservation`
The heart of the domain.

**Invariants** (enforced in the constructor): ids non-blank, `start < end`, amount ≥ 0.

**Key methods**

| Method | Contract |
|---|---|
| `transition(expected, next)` | `synchronized` CAS. Returns `false` instead of throwing, so callers choose the policy (retry, ignore, throw). |
| `isHoldExpired(now)` | `PENDING_PAYMENT && !holdExpiresAt.isAfter(now)` |
| `blocksInterval(now)` | Exhaustive `switch` over status. Adding a status becomes a **compile error** until classified. |
| `overlaps(s, e)` | `start < e && s < end` |

**Why `quotedAmount` is on the reservation:** price is fixed at hold time. A rate change between hold and payment must not change what the user is charged.

**Why `holdExpiresAt` exists even after confirmation:** it is ignored once the status leaves `PENDING_PAYMENT`.

### 5.4 `Payment`
- One payment per reservation (enforced by the repository), identified externally by its `idempotencyKey`.
- Transition guards live on the entity: `markSucceeded` rejects `REFUNDED`; `markFailed` never overwrites `SUCCEEDED`/`REFUNDED`; `markRefunded` only from `SUCCEEDED`, returns `false` if already refunded.
- `providerTransactionId` is set **before** the status flips so a reader that sees `SUCCEEDED` always sees the transaction id (both fields are `volatile`; status is written last).

### 5.5 `PricingStrategy` / `DailyPricingStrategy`
Strategy pattern: one method, easily swapped. `DailyPricingStrategy` rounds **up** to started days, uses `BigDecimal` (never `double` for money), `HALF_UP` at 2 decimals, and copies the rate map defensively. Unknown category fails loudly rather than pricing at zero.

### 5.6 Repositories
Interfaces describe *what the domain needs*, not generic CRUD.

- `CarRepository.findCandidates(category, location)`: operational only, case-insensitive, sorted by id → **deterministic assignment** (lowest id first).
- `ReservationRepository.hasOverlappingBlockingReservation(carId, start, end, now)`: the availability primitive. `now` is a parameter so the clock stays injectable.
- `PaymentRepository.saveIfAbsent`: **atomic get-or-create**. The first draft did `findByIdempotencyKey` then `save`, which is a check-then-act race of exactly the kind this project exists to avoid.

`InMemoryReservationRepository.findByCarId` / overlap scans are O(total reservations). Fine for a demo; a real store indexes `(car_id, start, end)`.

### 5.7 `PaymentGateway`, `GatewayResult`, `DemoPaymentGateway`
The gateway is a **port**: the domain depends on the interface, and Stripe/Razorpay would be an adapter. `DemoPaymentGateway` memoizes results per idempotency key (as real providers do) and deduplicates refunds. The verification suite uses a `ScriptedGateway` that can decline charges or run a hook mid-charge.

### 5.8 `PaymentService`
- `chargeReservation`: build candidate `Payment(PROCESSING)` → `saveIfAbsent` → if already `SUCCEEDED`/`REFUNDED` return it → else call gateway → mark result. Safe to call repeatedly.
- `refund`: idempotent; refund key = `"refund-" + paymentId` so a retried refund can't pay out twice at the provider.

### 5.9 `BookingService`

| Method | Purpose | Lock held? |
|---|---|---|
| `searchAvailableCars` | Advisory list | per car, briefly |
| `createHold` | Authoritative check + save hold | per candidate car |
| `confirmBooking` | Charge, then CAS to `CONFIRMED` | **not** during charge; yes during final CAS |
| `cancelBooking` | CAS to `CANCELLED`, then refund | CAS only; refund outside |
| `pickup` / `returnCar` | Lifecycle | yes |
| `expirePendingHolds` | Sweeper | per reservation |

The private `withCarLock(carId, supplier)` guarantees unlock via `finally` in exactly one place, instead of eight copies of `lock(); try {...} finally { unlock(); }`.

`tryHold` is only ever called from inside `withCarLock`. It returns `null` for "this car isn't free" so the loop in `createHold` can move on to the next candidate.

## 6. Cross-cutting decisions

### 6.1 Why a hold instead of "pay then reserve" or "reserve then pay forever"?
- *Pay then reserve* → two people can pay for one car; one must be refunded.
- *Reserve with no expiry* → abandoned checkouts lock inventory.
- *Hold with expiry* → inventory is protected briefly; abandonment self-heals.

### 6.2 Why derive availability instead of storing it?
A stored `available` flag must be flipped at the right moment in every path (expiry, cancel, failure, return) and any missed path = permanent inconsistency. Deriving from reservations means the reservation list is the **single source of truth**.

### 6.3 Why per-car locks, not one global lock?
Bookings for different cars don't conflict. A global lock serialises the whole fleet. A per-car lock limits contention to the actual conflict set. `locksByCarId` grows with the fleet (bounded, small).

### 6.4 Why no I/O under a lock?
The PSP can take seconds or hang. Holding a lock across it turns one slow call into a stall for every booking on that car. We pay for this with the refund-compensation path.

### 6.5 Why typed exceptions?
Callers (an HTTP layer, say) need to map "no car" → 409, "hold expired" → 410, "payment failed" → 402 without parsing messages. The first draft threw `IllegalStateException` for all of them.

### 6.6 Why an injectable `Clock`?
Hold expiry and pickup depend on "now". With `Instant.now()` hard-coded, tests either `sleep` (slow, flaky) or can't cover those paths. The `Clock` makes every time-based rule deterministic.

### 6.7 Why relationships by id?
`Reservation` holds `carId` and `customerId`, not object references. Aggregates stay independent, serialisation is trivial, and it mirrors foreign keys. The cost is an extra lookup when you need the `Car`.

### 6.8 Patterns present (and honestly not present)

| Pattern | Where |
|---|---|
| Strategy | `PricingStrategy` |
| Repository | `*Repository` + `InMemory*` |
| Ports & adapters | `PaymentGateway` / `DemoPaymentGateway` |
| Dependency injection | constructor injection in `BookingService`, `PaymentService` |
| State (lightweight) | `ReservationStatus` + guarded `transition`; *not* the GoF State class-per-state |
| Compensating transaction | refund on failed confirmation |
| Idempotent receiver | idempotency keys |
| **Not used** | Factory, Observer, Builder: they would be speculative here |

## 7. Known flaws, explained in depth

These are deliberate scope limits. Knowing them is part of the design.

1. **Single-JVM locking.** Two app instances each have their own `ReentrantLock` and can double-book. *Fix:* DB exclusion constraint / `SELECT … FOR UPDATE` on the car row, or a distributed lock with fencing.
2. **Crash between charge and confirm.** If the process dies after the gateway charged but before the CAS, the customer is charged with a `PENDING_PAYMENT` booking that will expire. *Fix:* persist `Payment(PROCESSING)` before calling the gateway (done in-memory), then run a **reconciliation job** over `PROCESSING`/`SUCCEEDED`-without-`CONFIRMED` payments, plus PSP webhooks.
3. **Uncertain gateway outcomes.** A timeout from the gateway is neither success nor failure. Today it would propagate as an exception. *Fix:* treat as `PROCESSING`, then query the PSP by idempotency key / await webhook.
4. **Failed payment cancels the hold.** A user who mistyped a card must start over. *Fix:* keep the hold alive until expiry and allow a retry with a *new* attempt (this needs a `Payment` per attempt rather than one per reservation).
5. **Maintenance vs existing bookings.** `markMaintenance()` hides the car from new searches but does not touch confirmed reservations, and `confirmBooking` will refuse to confirm if the car went non-operational. *Fix:* a reassign-or-cancel policy.
6. **No late-return or extension handling.** `returnCar` does not compare actual to planned end, and nothing stops the next booking starting while the car is still out.
7. **`pickup` has no upper bound.** A no-show stays `CONFIRMED` forever and blocks the car. *Fix:* no-show policy that releases after a grace period.
8. **Linear scans.** Repository queries are O(n).
9. **Lock map never shrinks.** One `ReentrantLock` per car id, which is fine for a fleet.
10. **Prices are in an unspecified currency**, with no tax or deposit.
11. **Time zones.** Everything is `Instant` (UTC). "A day" in `DailyPricingStrategy` is 24 hours, not a calendar day in the customer's zone.
12. **Customer is not verified to exist.** `createHold` trusts the `Customer` it is given.

## 8. How the pieces would change

| Change | Impact |
|---|---|
| Hourly or surge pricing | New `PricingStrategy`; nothing else changes |
| Different PSP | New `PaymentGateway` |
| Postgres | New repositories; `withCarLock` replaced by a transaction + exclusion constraint; `Reservation.transition` becomes `UPDATE … WHERE status = ?` and checks the row count |
| Specific car selection (not just category) | `createHold` takes an optional `carId`; reuses `tryHold` |
| Pickup/drop-off at different locations | `Reservation` gains `pickupLocationId` / `dropoffLocationId`; availability must reason about where a car *will be* |
| Add-ons (GPS, child seat) | New inventory type with its own availability; price strategy takes add-ons |
| Notifications | Publish domain events from each transition |
| REST API | Map exceptions to HTTP codes; `Idempotency-Key` header → `paymentIdempotencyKey` |

## 9. Improvements made over the first draft

The design started from a single-file reference implementation. Splitting it into packages surfaced the following; each is covered by a test in `CarRentalVerification`.

| # | Finding | Change |
|---|---|---|
| 1 | **Race: duplicate `confirmBooking` with the same key could refund a good payment.** Two threads both charge (idempotent, same payment); the first confirms; the second sees status `CONFIRMED`, concludes the hold "is no longer valid", and **refunds the winner's money**. | Final section returns early if the reservation is already `CONFIRMED`. Test: 16 concurrent confirms. |
| 2 | **Payment repository could leave orphans and split on races.** `save` wrote the idempotency map then checked the reservation map (could throw after the first write); two same-key threads created two `Payment` objects and one threw. | Single atomic `saveIfAbsent` returning the winner; both maps updated under one monitor. |
| 3 | Bookings could start **in the past**. | `createHold` rejects `start < now`. |
| 4 | `Instant.now()` hard-coded → hold expiry / pickup untestable without sleeping. | Injectable `Clock`. |
| 5 | Everything threw `IllegalStateException`. | Typed `CarRentalException` hierarchy. |
| 6 | Hold time was computed with a second `Instant.now()` after the availability check. | One `now` per critical section. |
| 7 | Eight copies of `lock/try/finally/unlock`. | One `withCarLock` helper. |
| 8 | `markSucceeded` set status before the provider id. | Id first, status last. |
| 9 | `Car.markMaintenance()` could resurrect a retired car. | Throws for `RETIRED`. |
| 10 | `expirePendingHolds` returned nothing. | Returns the count expired. |
| 11 | Names: `payAndConfirm`, `createBooking` (creates a *hold*). | `confirmBooking`, `createHold`, matching the original class diagram. |
| 12 | Demo gateway printed a refund line twice if called twice. | Refunds deduplicated by refund key. |

## 10. Suggestions for next steps

Ordered by value for the effort.

1. **Persistence with a DB exclusion constraint** (Postgres `tstzrange` + `EXCLUDE USING gist`). This is the single change that makes it correct across instances.
2. **Reconciliation job + gateway webhooks** to close the crash-between-charge-and-confirm window (§7.2–7.3).
3. **Payment attempts** (many per reservation) so a declined card can be retried within the hold.
4. **Scheduled hold sweeper** (`ScheduledExecutorService` every ~30 s) purely to tidy statuses and free memory.
5. **Cancellation policy** (free until 24 h before, partial fee after): a `CancellationPolicy` strategy returning a refund amount; `PaymentService.refund` takes an amount.
6. **Late-return fees & no-show release.**
7. **Buffer time between rentals** (cleaning/inspection): extend `overlaps` with a configurable gap.
8. **Domain events** (`ReservationConfirmed`, `ReservationCancelled`…) for email/SMS and analytics, instead of coupling notifications into `BookingService`.
9. **Car-specific search results** with price quotes (`CarOffer(car, price)`), so the UI can show the price before the hold.
10. **Test framework.** The no-framework runner matches the sibling projects, but JUnit 5 would give better failure reporting if this grows.

## 11. Self-test

Try to answer without looking back.

1. Why is search "advisory" while `createHold` is "authoritative"?
2. Rentals [10h, 20h) and [20h, 30h) on the same car: allowed? Why does the interval type matter?
3. What exactly breaks if the payment gateway call is made *inside* the car lock?
4. A hold expires while the charge is in flight. Walk through what happens, step by step, and who ends up with the money.
5. Why must the second of two concurrent same-key `confirmBooking` calls *not* refund?
6. Why is `saveIfAbsent` needed instead of `find` then `save`?
7. Why does availability not require a background job?
8. Why store `quotedAmount` on the reservation?
9. Cancel changes state first and refunds second. What is the failure mode if you reverse the order?
10. What would you change to make this safe across 3 app servers?

<details>
<summary>Answers (short)</summary>

1. Results can be stale the instant they're returned; only a check under the car lock, immediately before saving, can't be invalidated.
2. Allowed. With half-open intervals touching endpoints don't overlap; closed intervals would forbid back-to-back rentals.
3. A slow/hung PSP blocks every booking for that car; deadlock-style stalls.
4. Charge succeeds → final lock section sees hold expired → CAS not attempted → `refund` → exception. The customer is refunded; reservation stays unconfirmed.
5. The first call's payment is the *same* payment that confirmed the booking; refunding it would take back money for a valid booking.
6. Between the two calls another thread can insert; `saveIfAbsent` is atomic so both callers end up with the same single `Payment`.
7. An expired hold is simply ignored by `blocksInterval(now)`; the sweeper only tidies statuses.
8. So the charged amount always equals the quoted amount, even if rates change meanwhile.
9. Refund-then-cancel could refund money and then fail to cancel (or crash), leaving an active booking that's been refunded. Cancel-then-refund fails safe: a recoverable "cancelled but not yet refunded".
10. DB-level exclusion constraint (or row lock) instead of the in-JVM lock; `UPDATE … WHERE status = ?` for transitions; persisted payments + reconciliation.

</details>

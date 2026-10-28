package com.carrental;

import com.carrental.enums.CarStatus;
import com.carrental.enums.PaymentStatus;
import com.carrental.enums.ReservationStatus;
import com.carrental.exception.HoldExpiredException;
import com.carrental.exception.InvalidReservationStateException;
import com.carrental.exception.NoAvailableCarException;
import com.carrental.exception.PaymentFailedException;
import com.carrental.exception.ReservationNotFoundException;
import com.carrental.model.Car;
import com.carrental.model.Customer;
import com.carrental.model.Payment;
import com.carrental.model.Reservation;
import com.carrental.payment.DemoPaymentGateway;
import com.carrental.payment.GatewayResult;
import com.carrental.payment.PaymentGateway;
import com.carrental.payment.PaymentService;
import com.carrental.repository.InMemoryCarRepository;
import com.carrental.repository.InMemoryPaymentRepository;
import com.carrental.repository.InMemoryReservationRepository;
import com.carrental.service.BookingService;
import com.carrental.strategy.DailyPricingStrategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runnable checks for core car-rental behavior (no external test framework). */
public final class CarRentalVerification {
    private static final String LOC = "CHENNAI-01";
    private static final Duration HOLD = Duration.ofMinutes(5);
    private static final Instant T0 = Instant.parse("2030-01-01T10:00:00Z");

    private static int passed;
    private static int failed;

    private CarRentalVerification() {
    }

    public static void main(String[] args) throws Exception {
        checkPricing();
        checkSearchFiltersByCategoryLocationAndStatus();
        checkHappyPath();
        checkOverlapRejectedAndBackToBackAllowed();
        checkSecondCarAssignedWhenFirstBusy();
        checkHoldBlocksThenExpiresAndFreesCar();
        checkPaymentFailureReleasesCar();
        checkConfirmIsIdempotent();
        checkIdempotencyKeyReuseAcrossReservationsRejected();
        checkCancelConfirmedRefundsAndIsIdempotent();
        checkPickupAndReturnLifecycle();
        checkPaidButHoldExpiredMidPaymentIsRefunded();
        checkCancelledMidPaymentIsRefunded();
        checkSweeperExpiresHolds();
        checkInputValidation();
        checkCarLifecycleRules();
        checkConcurrentHoldsOnSameCarYieldOneWinner();
        checkConcurrentConfirmSameKeyDoesNotRefund();

        System.out.println("Verification: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------- fixtures ----------

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Scriptable gateway: can fail charges and run a hook while a charge is "in flight". */
    private static final class ScriptedGateway implements PaymentGateway {
        private final DemoPaymentGateway delegate = new DemoPaymentGateway();
        private final List<String> refunds = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger charges = new AtomicInteger();
        volatile boolean failCharges;
        volatile Runnable onCharge = () -> { };

        @Override
        public GatewayResult charge(String key, BigDecimal amount) {
            charges.incrementAndGet();
            onCharge.run();
            return failCharges ? new GatewayResult(false, null) : delegate.charge(key, amount);
        }

        @Override
        public void refund(String providerTransactionId, String refundKey) {
            refunds.add(refundKey);
        }
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock(T0);
        final InMemoryCarRepository cars = new InMemoryCarRepository();
        final InMemoryReservationRepository reservations = new InMemoryReservationRepository();
        final ScriptedGateway gateway = new ScriptedGateway();
        final PaymentService payments = new PaymentService(new InMemoryPaymentRepository(), gateway);
        final BookingService booking;
        final Customer alice = new Customer("CUS-1", "Alice");
        final Customer bob = new Customer("CUS-2", "Bob");

        Fixture() {
            booking = new BookingService(cars, reservations, payments, pricing(), HOLD, clock);
        }

        Car addCar(String id, String category) {
            Car car = new Car(id, "REG-" + id, "Model-" + id, category, LOC, CarStatus.ACTIVE);
            cars.save(car);
            return car;
        }

        Instant in(Duration d) { return T0.plus(d); }

        Reservation hold(Customer c, String category, Duration from, Duration to) {
            return booking.createHold(c, category, LOC, in(from), in(to));
        }
    }

    private static DailyPricingStrategy pricing() {
        return new DailyPricingStrategy(Map.of(
                "HATCHBACK", new BigDecimal("1500.00"),
                "SEDAN", new BigDecimal("2200.00")));
    }

    // ---------- checks ----------

    private static void checkPricing() {
        Car car = new Car("C", "R", "M", "HATCHBACK", LOC, CarStatus.ACTIVE);
        DailyPricingStrategy p = pricing();
        assertEquals("1 hour bills one day", "1500.00", p.calculatePrice(car, T0, T0.plus(Duration.ofHours(1))).toString());
        assertEquals("exactly 2 days", "3000.00", p.calculatePrice(car, T0, T0.plus(Duration.ofDays(2))).toString());
        assertEquals("25 hours bills 2 days", "3000.00", p.calculatePrice(car, T0, T0.plus(Duration.ofHours(25))).toString());
        assertThrows("unknown category has no rate", IllegalArgumentException.class,
                () -> p.calculatePrice(new Car("C2", "R2", "M", "BUS", LOC, CarStatus.ACTIVE), T0, T0.plusSeconds(60)));
    }

    private static void checkSearchFiltersByCategoryLocationAndStatus() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        f.addCar("S1", "SEDAN");
        Car broken = f.addCar("H2", "HATCHBACK");
        broken.markMaintenance();
        f.cars.save(new Car("H3", "REG-H3", "M", "HATCHBACK", "MUMBAI-01", CarStatus.ACTIVE));

        List<Car> found = f.booking.searchAvailableCars("hatchback", LOC, f.in(Duration.ofHours(1)), f.in(Duration.ofDays(1)));
        assertEquals("only the active hatchback in this location", 1, found.size());
        assertEquals("it is H1", "H1", found.get(0).getId());
    }

    private static void checkHappyPath() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(1), Duration.ofHours(49));

        assertEquals("new reservation is PENDING_PAYMENT", ReservationStatus.PENDING_PAYMENT, r.getStatus());
        assertEquals("quote is 2 days", "3000.00", r.getQuotedAmount().toString());
        assertEquals("hold expires after hold duration", f.clock.instant().plus(HOLD), r.getHoldExpiresAt());

        Reservation confirmed = f.booking.confirmBooking(r.getId(), "k1");
        assertEquals("confirmed", ReservationStatus.CONFIRMED, confirmed.getStatus());
        Payment p = f.payments.findByReservationId(r.getId()).orElseThrow();
        assertEquals("payment succeeded", PaymentStatus.SUCCEEDED, p.getStatus());
        assertEquals("payment amount equals quote", r.getQuotedAmount(), p.getAmount());
        assertTrue("confirmed car is not searchable", f.booking
                .searchAvailableCars("HATCHBACK", LOC, f.in(Duration.ofHours(2)), f.in(Duration.ofHours(3))).isEmpty());
    }

    private static void checkOverlapRejectedAndBackToBackAllowed() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        f.hold(f.alice, "HATCHBACK", Duration.ofHours(10), Duration.ofHours(20));

        assertThrows("overlapping interval rejected", NoAvailableCarException.class,
                () -> f.hold(f.bob, "HATCHBACK", Duration.ofHours(15), Duration.ofHours(25)));
        assertThrows("contained interval rejected", NoAvailableCarException.class,
                () -> f.hold(f.bob, "HATCHBACK", Duration.ofHours(12), Duration.ofHours(14)));
        assertThrows("enclosing interval rejected", NoAvailableCarException.class,
                () -> f.hold(f.bob, "HATCHBACK", Duration.ofHours(5), Duration.ofHours(30)));

        Reservation after = f.hold(f.bob, "HATCHBACK", Duration.ofHours(20), Duration.ofHours(30));
        Reservation before = f.hold(f.bob, "HATCHBACK", Duration.ofHours(1), Duration.ofHours(10));
        assertTrue("back-to-back rentals on the same car are allowed",
                after.getCarId().equals(before.getCarId()));
    }

    private static void checkSecondCarAssignedWhenFirstBusy() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        f.addCar("H2", "HATCHBACK");
        Reservation a = f.hold(f.alice, "HATCHBACK", Duration.ofHours(1), Duration.ofHours(5));
        Reservation b = f.hold(f.bob, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(6));
        assertEquals("first booking gets the lowest id", "H1", a.getCarId());
        assertEquals("second booking falls through to next car", "H2", b.getCarId());
        assertThrows("third has nothing left", NoAvailableCarException.class,
                () -> f.hold(f.alice, "HATCHBACK", Duration.ofHours(3), Duration.ofHours(4)));
    }

    private static void checkHoldBlocksThenExpiresAndFreesCar() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation a = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        f.clock.advance(HOLD.minusSeconds(1));
        assertThrows("live hold blocks others", NoAvailableCarException.class,
                () -> f.hold(f.bob, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8)));

        f.clock.advance(Duration.ofSeconds(1));
        assertTrue("expired hold no longer blocks search", !f.booking
                .searchAvailableCars("HATCHBACK", LOC, f.in(Duration.ofHours(2)), f.in(Duration.ofHours(8))).isEmpty());
        assertThrows("paying after expiry fails", HoldExpiredException.class,
                () -> f.booking.confirmBooking(a.getId(), "late"));
        assertEquals("reservation marked EXPIRED", ReservationStatus.EXPIRED, a.getStatus());
        assertEquals("no charge attempted", 0, f.gateway.charges.get());
        f.hold(f.bob, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));
    }

    private static void checkPaymentFailureReleasesCar() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        f.gateway.failCharges = true;
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        assertThrows("failed payment surfaces", PaymentFailedException.class,
                () -> f.booking.confirmBooking(r.getId(), "k"));
        assertEquals("reservation cancelled", ReservationStatus.CANCELLED, r.getStatus());
        assertEquals("payment FAILED", PaymentStatus.FAILED, f.payments.findByReservationId(r.getId()).orElseThrow().getStatus());
        assertTrue("no refund for a payment that never succeeded", f.gateway.refunds.isEmpty());
        f.hold(f.bob, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));
    }

    private static void checkConfirmIsIdempotent() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        f.booking.confirmBooking(r.getId(), "k");
        Reservation again = f.booking.confirmBooking(r.getId(), "k");
        assertEquals("second confirm returns same reservation", r, again);
        assertEquals("provider charged exactly once", 1, f.gateway.charges.get());
        assertTrue("nothing refunded", f.gateway.refunds.isEmpty());
        assertThrows("unknown reservation", ReservationNotFoundException.class,
                () -> f.booking.confirmBooking("nope", "k"));
    }

    private static void checkIdempotencyKeyReuseAcrossReservationsRejected() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        f.addCar("H2", "HATCHBACK");
        Reservation a = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));
        Reservation b = f.hold(f.bob, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        f.booking.confirmBooking(a.getId(), "shared-key");
        assertThrows("same key for another reservation rejected", IllegalArgumentException.class,
                () -> f.booking.confirmBooking(b.getId(), "shared-key"));
        assertEquals("other reservation untouched", ReservationStatus.PENDING_PAYMENT, b.getStatus());
    }

    private static void checkCancelConfirmedRefundsAndIsIdempotent() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));
        f.booking.confirmBooking(r.getId(), "k");

        f.booking.cancelBooking(r.getId());
        assertEquals("cancelled", ReservationStatus.CANCELLED, r.getStatus());
        assertEquals("payment refunded", PaymentStatus.REFUNDED, f.payments.findByReservationId(r.getId()).orElseThrow().getStatus());
        assertEquals("one refund sent", 1, f.gateway.refunds.size());

        f.booking.cancelBooking(r.getId());
        assertEquals("second cancel is a no-op", 1, f.gateway.refunds.size());
        f.hold(f.bob, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        Reservation pending = f.hold(f.alice, "HATCHBACK", Duration.ofHours(20), Duration.ofHours(30));
        f.booking.cancelBooking(pending.getId());
        assertEquals("pending hold can be cancelled without refund", 1, f.gateway.refunds.size());
    }

    private static void checkPickupAndReturnLifecycle() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        assertThrows("cannot pick up unpaid reservation", InvalidReservationStateException.class,
                () -> f.booking.pickup(r.getId()));
        f.booking.confirmBooking(r.getId(), "k");
        assertThrows("cannot pick up before start", InvalidReservationStateException.class,
                () -> f.booking.pickup(r.getId()));

        f.clock.advance(Duration.ofHours(2));
        f.booking.pickup(r.getId());
        assertEquals("picked up", ReservationStatus.PICKED_UP, r.getStatus());
        assertThrows("cannot cancel after pickup", InvalidReservationStateException.class,
                () -> f.booking.cancelBooking(r.getId()));
        assertTrue("picked-up car still blocks", f.booking.searchAvailableCars("HATCHBACK", LOC,
                f.in(Duration.ofHours(3)), f.in(Duration.ofHours(4))).isEmpty());

        f.booking.returnCar(r.getId());
        assertEquals("completed", ReservationStatus.COMPLETED, r.getStatus());
        assertThrows("cannot return twice", InvalidReservationStateException.class,
                () -> f.booking.returnCar(r.getId()));
    }

    private static void checkPaidButHoldExpiredMidPaymentIsRefunded() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        f.gateway.onCharge = () -> f.clock.advance(HOLD.plusSeconds(1));
        assertThrows("late confirmation is rejected", InvalidReservationStateException.class,
                () -> f.booking.confirmBooking(r.getId(), "k"));
        assertEquals("reservation is not confirmed", ReservationStatus.PENDING_PAYMENT, r.getStatus());
        assertEquals("customer is refunded", PaymentStatus.REFUNDED, f.payments.findByReservationId(r.getId()).orElseThrow().getStatus());
    }

    private static void checkCancelledMidPaymentIsRefunded() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        f.gateway.onCharge = () -> f.booking.cancelBooking(r.getId());
        assertThrows("confirmation loses to cancellation", InvalidReservationStateException.class,
                () -> f.booking.confirmBooking(r.getId(), "k"));
        assertEquals("stays cancelled", ReservationStatus.CANCELLED, r.getStatus());
        assertEquals("payment refunded", PaymentStatus.REFUNDED, f.payments.findByReservationId(r.getId()).orElseThrow().getStatus());
    }

    private static void checkSweeperExpiresHolds() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        f.addCar("H2", "HATCHBACK");
        Reservation a = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));
        Reservation b = f.hold(f.bob, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));
        f.booking.confirmBooking(b.getId(), "k");

        f.clock.advance(HOLD);
        assertEquals("sweeper expires only the unpaid hold", 1, f.booking.expirePendingHolds());
        assertEquals("unpaid expired", ReservationStatus.EXPIRED, a.getStatus());
        assertEquals("paid untouched", ReservationStatus.CONFIRMED, b.getStatus());
        assertEquals("sweeper is repeatable", 0, f.booking.expirePendingHolds());
    }

    private static void checkInputValidation() {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        assertThrows("start must precede end", IllegalArgumentException.class,
                () -> f.hold(f.alice, "HATCHBACK", Duration.ofHours(5), Duration.ofHours(5)));
        assertThrows("start in the past", IllegalArgumentException.class,
                () -> f.hold(f.alice, "HATCHBACK", Duration.ofHours(-1), Duration.ofHours(5)));
        assertThrows("blank customer id", IllegalArgumentException.class, () -> new Customer(" ", "x"));
        assertThrows("duplicate car id", IllegalArgumentException.class, () -> f.addCar("H1", "SEDAN"));
        assertThrows("non-positive hold duration", IllegalArgumentException.class,
                () -> new BookingService(f.cars, f.reservations, f.payments, pricing(), Duration.ZERO));
        assertThrows("negative quote", IllegalArgumentException.class, () -> new Reservation(
                "R", "C", "CAR", T0, T0.plusSeconds(1), T0, new BigDecimal("-1"), ReservationStatus.PENDING_PAYMENT));
    }

    private static void checkCarLifecycleRules() {
        Car car = new Car("C", "R", "M", "SEDAN", LOC, CarStatus.ACTIVE);
        car.markMaintenance();
        assertTrue("maintenance car is not operational", !car.isOperational());
        car.markActive();
        assertTrue("reactivated", car.isOperational());
        car.retire();
        assertThrows("retired car cannot be reactivated", IllegalStateException.class, car::markActive);
        assertThrows("retired car cannot go to maintenance", IllegalStateException.class, car::markMaintenance);
    }

    private static void checkConcurrentHoldsOnSameCarYieldOneWinner() throws Exception {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Customer c = new Customer("CUS-" + i, "User" + i);
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    f.hold(c, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));
                    wins.incrementAndGet();
                } catch (NoAvailableCarException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        try {
            for (Future<?> fut : futures) {
                fut.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals("exactly one concurrent hold wins", 1, wins.get());
        assertEquals("all others rejected", threads - 1, rejected.get());
        assertEquals("only one reservation stored", 1, f.reservations.findByCarId("H1").size());
    }

    private static void checkConcurrentConfirmSameKeyDoesNotRefund() throws Exception {
        Fixture f = new Fixture();
        f.addCar("H1", "HATCHBACK");
        Reservation r = f.hold(f.alice, "HATCHBACK", Duration.ofHours(2), Duration.ofHours(8));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReservationStatus>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return f.booking.confirmBooking(r.getId(), "same-key").getStatus();
            }));
        }
        go.countDown();
        try {
            for (Future<ReservationStatus> fut : futures) {
                assertEquals("every retry sees CONFIRMED", ReservationStatus.CONFIRMED, fut.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue("losing retries must not refund the winning payment", f.gateway.refunds.isEmpty());
        assertEquals("payment still SUCCEEDED", PaymentStatus.SUCCEEDED,
                f.payments.findByReservationId(r.getId()).orElseThrow().getStatus());
    }

    // ---------- tiny assertion helpers ----------

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected.equals(actual);
        assertTrue(ok ? name : name + " (expected " + expected + ", got " + actual + ")", ok);
    }

    private static void assertThrows(String name, Class<? extends Throwable> type, ThrowingRunnable action) {
        try {
            action.run();
            assertTrue(name + " (nothing thrown)", false);
        } catch (Throwable t) {
            assertTrue(type.isInstance(t) ? name : name + " (got " + t + ")", type.isInstance(t));
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

package com.carrental.service;

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
import com.carrental.payment.PaymentService;
import com.carrental.repository.CarRepository;
import com.carrental.repository.ReservationRepository;
import com.carrental.strategy.PricingStrategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.carrental.util.Validation.requireInterval;

/**
 * Orchestrates the booking lifecycle. Per-car locks make "check overlap + create hold"
 * atomic within one JVM; payment I/O is always performed outside any lock.
 *
 * A JVM lock does not coordinate multiple application instances. A real deployment
 * must enforce non-overlap in shared storage (transaction + row/range lock, or a
 * database exclusion constraint on (car_id, tstzrange)).
 */
public final class BookingService {
    private final CarRepository carRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentService paymentService;
    private final PricingStrategy pricingStrategy;
    private final Duration holdDuration;
    private final Clock clock;
    private final Map<String, ReentrantLock> locksByCarId = new ConcurrentHashMap<>();

    public BookingService(CarRepository carRepository,
                          ReservationRepository reservationRepository,
                          PaymentService paymentService,
                          PricingStrategy pricingStrategy,
                          Duration holdDuration) {
        this(carRepository, reservationRepository, paymentService, pricingStrategy,
                holdDuration, Clock.systemUTC());
    }

    public BookingService(CarRepository carRepository,
                          ReservationRepository reservationRepository,
                          PaymentService paymentService,
                          PricingStrategy pricingStrategy,
                          Duration holdDuration,
                          Clock clock) {
        this.carRepository = Objects.requireNonNull(carRepository, "carRepository");
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "reservationRepository");
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService");
        this.pricingStrategy = Objects.requireNonNull(pricingStrategy, "pricingStrategy");
        this.holdDuration = Objects.requireNonNull(holdDuration, "holdDuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (holdDuration.isNegative() || holdDuration.isZero()) {
            throw new IllegalArgumentException("Hold duration must be positive");
        }
    }

    /**
     * Search is advisory: a result can become unavailable immediately.
     * {@link #createHold} performs the authoritative check again.
     */
    public List<Car> searchAvailableCars(String category, String locationId, Instant start, Instant end) {
        requireInterval(start, end);
        Instant now = clock.instant();

        return carRepository.findCandidates(category, locationId).stream()
                .filter(car -> withCarLock(car.getId(), () ->
                        car.isOperational()
                                && !reservationRepository.hasOverlappingBlockingReservation(
                                        car.getId(), start, end, now)))
                .toList();
    }

    /** Picks the first free car of the category and places a time-limited PENDING_PAYMENT hold. */
    public Reservation createHold(Customer customer, String category, String locationId,
                                  Instant start, Instant end) {
        Objects.requireNonNull(customer, "customer");
        requireInterval(start, end);
        if (start.isBefore(clock.instant())) {
            throw new IllegalArgumentException("Rental start must not be in the past");
        }

        for (Car car : carRepository.findCandidates(category, locationId)) {
            Reservation hold = withCarLock(car.getId(), () -> tryHold(customer, car, start, end));
            if (hold != null) {
                return hold;
            }
        }
        throw new NoAvailableCarException("No available " + category + " at " + locationId
                + " for " + start + " to " + end);
    }

    /** Must run under the car's lock. Returns null if this car is not free for the interval. */
    private Reservation tryHold(Customer customer, Car car, Instant start, Instant end) {
        // Revalidate inside the lock; candidate lists and search results are never authoritative.
        Instant now = clock.instant();
        if (!car.isOperational()
                || reservationRepository.hasOverlappingBlockingReservation(car.getId(), start, end, now)) {
            return null;
        }

        BigDecimal quote = pricingStrategy.calculatePrice(car, start, end);
        Reservation hold = new Reservation(
                UUID.randomUUID().toString(),
                customer.getId(),
                car.getId(),
                start,
                end,
                now.plus(holdDuration),
                quote,
                ReservationStatus.PENDING_PAYMENT);

        // Saving is the logical commit point of the hold.
        reservationRepository.save(hold);
        return hold;
    }

    /**
     * Charges the customer and confirms the reservation. Idempotent per
     * (reservation, idempotencyKey): retrying a confirmed booking returns it unchanged.
     */
    public Reservation confirmBooking(String reservationId, String paymentIdempotencyKey) {
        Reservation reservation = requireReservation(reservationId);

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return reservation;
        }
        if (reservation.getStatus() != ReservationStatus.PENDING_PAYMENT) {
            throw new InvalidReservationStateException(
                    "Cannot pay for reservation in state " + reservation.getStatus());
        }
        if (reservation.isHoldExpired(clock.instant())) {
            expireHold(reservation);
            throw new HoldExpiredException(reservationId);
        }

        // External call: deliberately outside the car lock.
        Payment payment = paymentService.chargeReservation(reservation, paymentIdempotencyKey);

        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            releasePendingHold(reservation);
            throw new PaymentFailedException("Payment failed for reservation " + reservationId);
        }

        boolean confirmed = withCarLock(reservation.getCarId(), () -> {
            if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
                return true; // a concurrent retry with the same key already confirmed it
            }
            boolean holdStillValid = reservation.getStatus() == ReservationStatus.PENDING_PAYMENT
                    && !reservation.isHoldExpired(clock.instant())
                    && carRepository.findById(reservation.getCarId())
                            .map(Car::isOperational).orElse(false);
            return holdStillValid && reservation.transition(
                    ReservationStatus.PENDING_PAYMENT, ReservationStatus.CONFIRMED);
        });

        if (confirmed) {
            return reservation;
        }

        // Paid, but the hold lapsed or the booking was cancelled mid-payment: give the money back.
        paymentService.refund(payment);
        throw new InvalidReservationStateException(
                "Payment succeeded but reservation could not be confirmed; refund issued");
    }

    public void cancelBooking(String reservationId) {
        Reservation reservation = requireReservation(reservationId);

        withCarLock(reservation.getCarId(), () -> {
            ReservationStatus current = reservation.getStatus();
            if (current == ReservationStatus.CANCELLED || current == ReservationStatus.EXPIRED) {
                return null;
            }
            if (current == ReservationStatus.PICKED_UP || current == ReservationStatus.COMPLETED) {
                throw new InvalidReservationStateException(
                        "Cannot cancel a reservation after pickup/completion");
            }
            if (!reservation.transition(current, ReservationStatus.CANCELLED)) {
                throw new InvalidReservationStateException("Reservation changed concurrently; retry");
            }
            return null;
        });

        paymentService.findByReservationId(reservationId)
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .ifPresent(paymentService::refund);
    }

    /** Hands over the keys. Allowed at or after the scheduled start. */
    public void pickup(String reservationId) {
        Reservation reservation = requireReservation(reservationId);

        withCarLock(reservation.getCarId(), () -> {
            if (clock.instant().isBefore(reservation.getStart())) {
                throw new InvalidReservationStateException("Pickup time has not arrived yet");
            }
            if (!reservation.transition(ReservationStatus.CONFIRMED, ReservationStatus.PICKED_UP)) {
                throw new InvalidReservationStateException(
                        "Only a confirmed reservation can be picked up (state: "
                                + reservation.getStatus() + ")");
            }
            return null;
        });
    }

    public void returnCar(String reservationId) {
        Reservation reservation = requireReservation(reservationId);

        withCarLock(reservation.getCarId(), () -> {
            if (!reservation.transition(ReservationStatus.PICKED_UP, ReservationStatus.COMPLETED)) {
                throw new InvalidReservationStateException(
                        "Only a picked-up reservation can be completed (state: "
                                + reservation.getStatus() + ")");
            }
            return null;
        });
    }

    /** Sweeper entry point. Correctness never depends on it: expired holds already stop blocking. */
    public int expirePendingHolds() {
        int expired = 0;
        for (Reservation reservation : reservationRepository.findExpiredHolds(clock.instant())) {
            if (expireHold(reservation)) {
                expired++;
            }
        }
        return expired;
    }

    private boolean expireHold(Reservation reservation) {
        return withCarLock(reservation.getCarId(), () ->
                reservation.isHoldExpired(clock.instant())
                        && reservation.transition(
                                ReservationStatus.PENDING_PAYMENT, ReservationStatus.EXPIRED));
    }

    /** After a failed payment: free the car now, or mark EXPIRED if the hold had lapsed anyway. */
    private void releasePendingHold(Reservation reservation) {
        withCarLock(reservation.getCarId(), () -> {
            ReservationStatus target = reservation.isHoldExpired(clock.instant())
                    ? ReservationStatus.EXPIRED
                    : ReservationStatus.CANCELLED;
            return reservation.transition(ReservationStatus.PENDING_PAYMENT, target);
        });
    }

    private Reservation requireReservation(String reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    private <T> T withCarLock(String carId, java.util.function.Supplier<T> action) {
        ReentrantLock lock = locksByCarId.computeIfAbsent(carId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}

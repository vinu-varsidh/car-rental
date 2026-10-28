package com.carrental.repository;

import com.carrental.enums.ReservationStatus;
import com.carrental.model.Reservation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReservationRepository implements ReservationRepository {
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

    @Override
    public void save(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        Reservation previous = reservations.putIfAbsent(reservation.getId(), reservation);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate reservation id: " + reservation.getId());
        }
    }

    @Override
    public Optional<Reservation> findById(String reservationId) {
        return Optional.ofNullable(reservations.get(reservationId));
    }

    @Override
    public List<Reservation> findByCarId(String carId) {
        return reservations.values().stream()
                .filter(r -> r.getCarId().equals(carId))
                .sorted(Comparator.comparing(Reservation::getStart))
                .toList();
    }

    @Override
    public boolean hasOverlappingBlockingReservation(String carId, Instant start, Instant end, Instant now) {
        return reservations.values().stream()
                .filter(r -> r.getCarId().equals(carId))
                .anyMatch(r -> r.blocksInterval(now) && r.overlaps(start, end));
    }

    @Override
    public List<Reservation> findExpiredHolds(Instant now) {
        return reservations.values().stream()
                .filter(r -> r.getStatus() == ReservationStatus.PENDING_PAYMENT)
                .filter(r -> r.isHoldExpired(now))
                .toList();
    }
}

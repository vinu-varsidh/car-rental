package com.carrental.repository;

import com.carrental.model.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository {
    void save(Reservation reservation);

    Optional<Reservation> findById(String reservationId);

    List<Reservation> findByCarId(String carId);

    /**
     * The caller must run this check and the following {@link #save} under the
     * same per-car lock. The in-memory implementation is single-JVM only; a real
     * store must enforce this with a transaction or an exclusion constraint.
     */
    boolean hasOverlappingBlockingReservation(String carId, Instant start, Instant end, Instant now);

    /** PENDING_PAYMENT reservations whose hold has lapsed. */
    List<Reservation> findExpiredHolds(Instant now);
}

package com.carrental.repository;

import com.carrental.model.Payment;

import java.util.Optional;

public interface PaymentRepository {
    Optional<Payment> findByIdempotencyKey(String key);

    Optional<Payment> findByReservationId(String reservationId);

    /**
     * Atomically stores {@code candidate} unless a payment with the same
     * idempotency key already exists for the same reservation.
     *
     * @return the stored payment (the existing one when the key was already used)
     * @throws IllegalArgumentException if the key belongs to another reservation,
     *         or the reservation already has a payment under a different key
     */
    Payment saveIfAbsent(Payment candidate);
}

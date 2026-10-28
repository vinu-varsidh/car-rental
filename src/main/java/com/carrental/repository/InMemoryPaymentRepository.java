package com.carrental.repository;

import com.carrental.model.Payment;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Both indexes are updated together under one monitor so they can never diverge. */
public final class InMemoryPaymentRepository implements PaymentRepository {
    private final Map<String, Payment> paymentsByIdempotencyKey = new HashMap<>();
    private final Map<String, String> keyByReservationId = new HashMap<>();

    @Override
    public synchronized Optional<Payment> findByIdempotencyKey(String key) {
        return Optional.ofNullable(paymentsByIdempotencyKey.get(key));
    }

    @Override
    public synchronized Optional<Payment> findByReservationId(String reservationId) {
        String key = keyByReservationId.get(reservationId);
        return key == null ? Optional.empty() : Optional.ofNullable(paymentsByIdempotencyKey.get(key));
    }

    @Override
    public synchronized Payment saveIfAbsent(Payment candidate) {
        Objects.requireNonNull(candidate, "payment");

        Payment existing = paymentsByIdempotencyKey.get(candidate.getIdempotencyKey());
        if (existing != null) {
            if (!existing.getReservationId().equals(candidate.getReservationId())) {
                throw new IllegalArgumentException("Idempotency key already belongs to another reservation");
            }
            return existing;
        }

        String existingKey = keyByReservationId.get(candidate.getReservationId());
        if (existingKey != null) {
            throw new IllegalArgumentException("A payment already exists for this reservation");
        }

        paymentsByIdempotencyKey.put(candidate.getIdempotencyKey(), candidate);
        keyByReservationId.put(candidate.getReservationId(), candidate.getIdempotencyKey());
        return candidate;
    }
}

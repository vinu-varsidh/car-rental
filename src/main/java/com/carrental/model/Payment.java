package com.carrental.model;

import com.carrental.enums.PaymentStatus;

import java.math.BigDecimal;
import java.util.Objects;

import static com.carrental.util.Validation.requireText;

public final class Payment {
    private final String id;
    private final String reservationId;
    private final String idempotencyKey;
    private final BigDecimal amount;
    private volatile PaymentStatus status;
    private volatile String providerTransactionId;

    public Payment(String id, String reservationId, String idempotencyKey,
                   BigDecimal amount, PaymentStatus status) {
        this.id = requireText(id, "payment id");
        this.reservationId = requireText(reservationId, "reservation id");
        this.idempotencyKey = requireText(idempotencyKey, "idempotency key");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.status = Objects.requireNonNull(status, "status");
    }

    public String getId() { return id; }
    public String getReservationId() { return reservationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderTransactionId() { return providerTransactionId; }

    public synchronized void markSucceeded(String providerTransactionId) {
        if (status == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Refunded payment cannot become succeeded");
        }
        this.providerTransactionId = requireText(providerTransactionId, "provider transaction id");
        status = PaymentStatus.SUCCEEDED;
    }

    public synchronized void markFailed() {
        if (status != PaymentStatus.SUCCEEDED && status != PaymentStatus.REFUNDED) {
            status = PaymentStatus.FAILED;
        }
    }

    /** @return true if this call performed the transition, false if already refunded. */
    public synchronized boolean markRefunded() {
        if (status == PaymentStatus.REFUNDED) {
            return false;
        }
        if (status != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException("Only a succeeded payment can be refunded");
        }
        status = PaymentStatus.REFUNDED;
        return true;
    }

    @Override
    public String toString() {
        return "Payment{id='" + id + "', reservationId='" + reservationId
                + "', amount=" + amount + ", status=" + status + "}";
    }
}

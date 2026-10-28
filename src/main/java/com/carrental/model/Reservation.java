package com.carrental.model;

import com.carrental.enums.ReservationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import static com.carrental.util.Validation.requireInterval;
import static com.carrental.util.Validation.requireText;

public final class Reservation {
    private final String id;
    private final String customerId;
    private final String carId;
    private final Instant start;
    private final Instant end;
    private final Instant holdExpiresAt;
    private final BigDecimal quotedAmount;
    private volatile ReservationStatus status;

    public Reservation(String id, String customerId, String carId,
                       Instant start, Instant end, Instant holdExpiresAt,
                       BigDecimal quotedAmount, ReservationStatus status) {
        this.id = requireText(id, "reservation id");
        this.customerId = requireText(customerId, "customer id");
        this.carId = requireText(carId, "car id");
        requireInterval(start, end);
        this.start = start;
        this.end = end;
        this.holdExpiresAt = Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
        this.quotedAmount = Objects.requireNonNull(quotedAmount, "quotedAmount");
        this.status = Objects.requireNonNull(status, "status");
        if (quotedAmount.signum() < 0) {
            throw new IllegalArgumentException("Quoted amount cannot be negative");
        }
    }

    public String getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getCarId() { return carId; }
    public Instant getStart() { return start; }
    public Instant getEnd() { return end; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
    public BigDecimal getQuotedAmount() { return quotedAmount; }
    public ReservationStatus getStatus() { return status; }

    /** Compare-and-set style transition: succeeds only if the current state is {@code expected}. */
    public synchronized boolean transition(ReservationStatus expected, ReservationStatus next) {
        if (status != expected) {
            return false;
        }
        status = next;
        return true;
    }

    public boolean isHoldExpired(Instant now) {
        return status == ReservationStatus.PENDING_PAYMENT && !holdExpiresAt.isAfter(now);
    }

    /** Whether this reservation currently makes the car unavailable for its interval. */
    public boolean blocksInterval(Instant now) {
        return switch (status) {
            case CONFIRMED, PICKED_UP -> true;
            case PENDING_PAYMENT -> holdExpiresAt.isAfter(now);
            case COMPLETED, CANCELLED, EXPIRED -> false;
        };
    }

    /** Half-open intervals [start, end): back-to-back rentals do not overlap. */
    public boolean overlaps(Instant requestedStart, Instant requestedEnd) {
        return start.isBefore(requestedEnd) && requestedStart.isBefore(end);
    }

    @Override
    public String toString() {
        return "Reservation{id='" + id + "', carId='" + carId
                + "', start=" + start + ", end=" + end
                + ", status=" + status + ", amount=" + quotedAmount + "}";
    }
}

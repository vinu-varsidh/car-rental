package com.carrental.payment;

import com.carrental.enums.PaymentStatus;
import com.carrental.model.Payment;
import com.carrental.model.Reservation;
import com.carrental.repository.PaymentRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns the payment lifecycle (charge, refund) and idempotency; knows nothing about cars. */
public final class PaymentService {
    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway gateway) {
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "paymentRepository");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * Charges the reservation's quoted amount. Safe to call repeatedly with the same
     * key: a settled payment is returned as-is and the provider is not charged twice.
     */
    public Payment chargeReservation(Reservation reservation, String idempotencyKey) {
        Payment payment = paymentRepository.saveIfAbsent(new Payment(
                UUID.randomUUID().toString(),
                reservation.getId(),
                idempotencyKey,
                reservation.getQuotedAmount(),
                PaymentStatus.PROCESSING));

        if (payment.getStatus() == PaymentStatus.SUCCEEDED
                || payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment;
        }

        GatewayResult result = gateway.charge(idempotencyKey, payment.getAmount());
        if (result.succeeded()) {
            payment.markSucceeded(result.transactionId());
        } else {
            payment.markFailed();
        }
        return payment;
    }

    public void refund(Payment payment) {
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return;
        }
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException("Cannot refund a payment that did not succeed");
        }
        // The refund key is derived from the payment id so a retry never double-refunds.
        gateway.refund(payment.getProviderTransactionId(), "refund-" + payment.getId());
        payment.markRefunded();
    }

    public Optional<Payment> findByReservationId(String reservationId) {
        return paymentRepository.findByReservationId(reservationId);
    }
}

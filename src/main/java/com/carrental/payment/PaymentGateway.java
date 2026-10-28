package com.carrental.payment;

import java.math.BigDecimal;

/** Port to an external payment provider. Implementations must honor idempotency keys. */
public interface PaymentGateway {
    GatewayResult charge(String idempotencyKey, BigDecimal amount);

    void refund(String providerTransactionId, String refundIdempotencyKey);
}

package com.carrental.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Always-succeeds demo gateway. It remembers results per idempotency key the way
 * a real provider would, so a repeated charge returns the original transaction.
 */
public final class DemoPaymentGateway implements PaymentGateway {
    private final Map<String, GatewayResult> chargeResults = new ConcurrentHashMap<>();
    private final Set<String> processedRefunds = ConcurrentHashMap.newKeySet();

    @Override
    public GatewayResult charge(String idempotencyKey, BigDecimal amount) {
        return chargeResults.computeIfAbsent(idempotencyKey,
                key -> new GatewayResult(true, "TXN-" + UUID.randomUUID()));
    }

    @Override
    public void refund(String providerTransactionId, String refundIdempotencyKey) {
        if (processedRefunds.add(refundIdempotencyKey)) {
            System.out.println("  [gateway] refund issued for " + providerTransactionId);
        }
    }
}

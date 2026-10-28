package com.carrental.payment;

public record GatewayResult(boolean succeeded, String transactionId) {
}

package com.carrental.strategy;

import com.carrental.model.Car;

import java.math.BigDecimal;
import java.time.Instant;

/** Strategy for quoting the rental price of a car over [start, end). */
public interface PricingStrategy {
    BigDecimal calculatePrice(Car car, Instant start, Instant end);
}

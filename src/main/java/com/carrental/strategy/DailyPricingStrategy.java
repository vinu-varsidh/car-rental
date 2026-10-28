package com.carrental.strategy;

import com.carrental.model.Car;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.carrental.util.Validation.requireInterval;

/**
 * Charges for each started 24-hour block (minimum one). Swap this out if the
 * business uses calendar days, hourly pricing, weekend surcharges, taxes, etc.
 */
public final class DailyPricingStrategy implements PricingStrategy {
    private static final long SECONDS_PER_DAY = Duration.ofDays(1).getSeconds();

    private final Map<String, BigDecimal> dailyRateByCategory;

    public DailyPricingStrategy(Map<String, BigDecimal> dailyRateByCategory) {
        this.dailyRateByCategory = Map.copyOf(dailyRateByCategory);
    }

    @Override
    public BigDecimal calculatePrice(Car car, Instant start, Instant end) {
        requireInterval(start, end);

        BigDecimal dailyRate = dailyRateByCategory.get(car.getCategory());
        if (dailyRate == null) {
            throw new IllegalArgumentException("No rate configured for category " + car.getCategory());
        }

        long seconds = Duration.between(start, end).getSeconds();
        long billableDays = Math.max(1, (seconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY);

        return dailyRate.multiply(BigDecimal.valueOf(billableDays)).setScale(2, RoundingMode.HALF_UP);
    }
}

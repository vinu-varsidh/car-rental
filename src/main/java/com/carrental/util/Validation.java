package com.carrental.util;

import java.time.Instant;
import java.util.Objects;

public final class Validation {

    private Validation() {
    }

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /** Rental intervals are half-open: [start, end). */
    public static void requireInterval(Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("Start must be before end");
        }
    }
}

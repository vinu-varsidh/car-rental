package com.carrental.exception;

/** Base type for all business-rule failures raised by the rental domain. */
public class CarRentalException extends RuntimeException {
    public CarRentalException(String message) {
        super(message);
    }
}

package com.carrental.exception;

/** The requested operation is not legal for the reservation's current state. */
public class InvalidReservationStateException extends CarRentalException {
    public InvalidReservationStateException(String message) {
        super(message);
    }
}

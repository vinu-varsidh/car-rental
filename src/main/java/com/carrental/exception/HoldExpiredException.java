package com.carrental.exception;

public class HoldExpiredException extends CarRentalException {
    public HoldExpiredException(String reservationId) {
        super("Reservation hold has expired: " + reservationId);
    }
}

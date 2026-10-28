package com.carrental.exception;

public class ReservationNotFoundException extends CarRentalException {
    public ReservationNotFoundException(String reservationId) {
        super("Reservation not found: " + reservationId);
    }
}

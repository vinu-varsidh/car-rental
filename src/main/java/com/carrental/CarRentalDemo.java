package com.carrental;

import com.carrental.enums.CarStatus;
import com.carrental.model.Car;
import com.carrental.model.Customer;
import com.carrental.model.Reservation;
import com.carrental.payment.DemoPaymentGateway;
import com.carrental.payment.PaymentService;
import com.carrental.repository.CarRepository;
import com.carrental.repository.InMemoryCarRepository;
import com.carrental.repository.InMemoryPaymentRepository;
import com.carrental.repository.InMemoryReservationRepository;
import com.carrental.repository.ReservationRepository;
import com.carrental.service.BookingService;
import com.carrental.strategy.DailyPricingStrategy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public final class CarRentalDemo {

    private CarRentalDemo() {
    }

    public static void main(String[] args) {
        CarRepository cars = new InMemoryCarRepository();
        ReservationRepository reservations = new InMemoryReservationRepository();

        cars.save(new Car("CAR-1", "TN01AB1234", "Swift", "HATCHBACK", "CHENNAI-01", CarStatus.ACTIVE));
        cars.save(new Car("CAR-2", "TN01CD5678", "City", "SEDAN", "CHENNAI-01", CarStatus.ACTIVE));
        cars.save(new Car("CAR-3", "TN01EF9012", "Creta", "SUV", "CHENNAI-01", CarStatus.ACTIVE));

        DailyPricingStrategy pricing = new DailyPricingStrategy(Map.of(
                "HATCHBACK", new BigDecimal("1500.00"),
                "SEDAN", new BigDecimal("2200.00"),
                "SUV", new BigDecimal("3200.00")));

        PaymentService payments = new PaymentService(new InMemoryPaymentRepository(), new DemoPaymentGateway());
        BookingService booking = new BookingService(cars, reservations, payments, pricing, Duration.ofMinutes(5));

        Customer customer = new Customer("CUS-1", "Vinu");
        Instant start = Instant.now().plus(Duration.ofHours(1));
        Instant end = start.plus(Duration.ofDays(2));

        System.out.println("1. Search HATCHBACK @ CHENNAI-01 for 2 days");
        booking.searchAvailableCars("HATCHBACK", "CHENNAI-01", start, end).forEach(c -> System.out.println("   " + c));

        System.out.println("2. Create hold (5 min to pay)");
        Reservation hold = booking.createHold(customer, "HATCHBACK", "CHENNAI-01", start, end);
        System.out.println("   " + hold);

        System.out.println("3. Search again: the held car is no longer offered");
        System.out.println("   available = " + booking.searchAvailableCars("HATCHBACK", "CHENNAI-01", start, end).size());

        System.out.println("4. Pay and confirm");
        Reservation confirmed = booking.confirmBooking(hold.getId(), "pay-" + hold.getId());
        System.out.println("   " + confirmed);

        System.out.println("5. Cancel (refund issued)");
        booking.cancelBooking(confirmed.getId());
        System.out.println("   status = " + confirmed.getStatus()
                + ", payment = " + payments.findByReservationId(confirmed.getId()).orElseThrow().getStatus());

        System.out.println("6. Reservations for " + confirmed.getCarId());
        reservations.findByCarId(confirmed.getCarId()).forEach(r -> System.out.println("   " + r));
    }
}

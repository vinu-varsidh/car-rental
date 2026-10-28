package com.carrental.model;

import com.carrental.enums.CarStatus;

import java.util.Objects;

import static com.carrental.util.Validation.requireText;

public final class Car {
    private final String id;
    private final String registrationNumber;
    private final String model;
    private final String category;
    private final String locationId;
    private volatile CarStatus status;

    public Car(String id, String registrationNumber, String model,
               String category, String locationId, CarStatus status) {
        this.id = requireText(id, "car id");
        this.registrationNumber = requireText(registrationNumber, "registration number");
        this.model = requireText(model, "model");
        this.category = requireText(category, "category");
        this.locationId = requireText(locationId, "location id");
        this.status = Objects.requireNonNull(status, "status");
    }

    public String getId() { return id; }
    public String getRegistrationNumber() { return registrationNumber; }
    public String getModel() { return model; }
    public String getCategory() { return category; }
    public String getLocationId() { return locationId; }
    public CarStatus getStatus() { return status; }

    public void markMaintenance() {
        if (status == CarStatus.RETIRED) {
            throw new IllegalStateException("Retired cars cannot be sent to maintenance");
        }
        status = CarStatus.MAINTENANCE;
    }

    public void markActive() {
        if (status == CarStatus.RETIRED) {
            throw new IllegalStateException("Retired cars cannot be reactivated");
        }
        status = CarStatus.ACTIVE;
    }

    public void retire() {
        status = CarStatus.RETIRED;
    }

    public boolean isOperational() {
        return status == CarStatus.ACTIVE;
    }

    @Override
    public String toString() {
        return id + " (" + model + ", " + category + ", " + locationId + ")";
    }
}

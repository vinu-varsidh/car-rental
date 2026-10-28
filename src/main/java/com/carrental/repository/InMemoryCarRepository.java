package com.carrental.repository;

import com.carrental.model.Car;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCarRepository implements CarRepository {
    private final Map<String, Car> cars = new ConcurrentHashMap<>();

    @Override
    public void save(Car car) {
        Objects.requireNonNull(car, "car");
        Car existing = cars.putIfAbsent(car.getId(), car);
        if (existing != null && existing != car) {
            throw new IllegalArgumentException("Duplicate car id: " + car.getId());
        }
    }

    @Override
    public Optional<Car> findById(String carId) {
        return Optional.ofNullable(cars.get(carId));
    }

    @Override
    public List<Car> findCandidates(String category, String locationId) {
        return cars.values().stream()
                .filter(Car::isOperational)
                .filter(c -> c.getCategory().equalsIgnoreCase(category))
                .filter(c -> c.getLocationId().equalsIgnoreCase(locationId))
                .sorted(Comparator.comparing(Car::getId))
                .toList();
    }

    @Override
    public List<Car> findAll() {
        return cars.values().stream().sorted(Comparator.comparing(Car::getId)).toList();
    }
}

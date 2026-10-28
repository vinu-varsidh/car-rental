package com.carrental.repository;

import com.carrental.model.Car;

import java.util.List;
import java.util.Optional;

public interface CarRepository {
    void save(Car car);

    Optional<Car> findById(String carId);

    /** Operational cars of the given category at the given location, ordered by id. */
    List<Car> findCandidates(String category, String locationId);

    List<Car> findAll();
}

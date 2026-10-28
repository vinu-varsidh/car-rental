package com.carrental.model;

import static com.carrental.util.Validation.requireText;

public final class Customer {
    private final String id;
    private final String name;

    public Customer(String id, String name) {
        this.id = requireText(id, "customer id");
        this.name = requireText(name, "customer name");
    }

    public String getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() {
        return "Customer{id='" + id + "', name='" + name + "'}";
    }
}

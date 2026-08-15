package com.example.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>M0 deliberately contains no entities, repositories, controllers, services or
 * security configuration. The schema is owned by Flyway, and the only thing this
 * class needs to prove is that the context starts against a real PostgreSQL with
 * all migrations applied.
 */
@SpringBootApplication
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}

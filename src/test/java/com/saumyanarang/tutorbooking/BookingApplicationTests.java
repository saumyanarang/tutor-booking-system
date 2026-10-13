package com.saumyanarang.tutorbooking;

import com.saumyanarang.tutorbooking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the whole application starts against a real PostgreSQL and Redis, Liquibase applies every
 * migration, and Hibernate validates that the entities match the resulting schema.
 */
class BookingApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}

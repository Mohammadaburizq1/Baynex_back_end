package com.byonix.shoplink.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One disposable database per test JVM; no connection to developer/production databases.
 *
 * Machines without Docker can instead name an empty, throwaway PostgreSQL database with
 * SHOPLINK_TEST_DATABASE_URL (+ _USERNAME / _PASSWORD). It must be localhost and is migrated and
 * written to by the tests, so never point it at a database whose data matters.
 */
public final class PostgresTestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final String EXTERNAL_URL = System.getenv("SHOPLINK_TEST_DATABASE_URL");

    private static final class Holder {
        static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
        static { DATABASE.start(); }
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if ("false".equals(context.getEnvironment().getProperty("spring.flyway.enabled"))) return;
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            if (!EXTERNAL_URL.matches("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:\\d+)?/.*")) {
                throw new IllegalStateException("SHOPLINK_TEST_DATABASE_URL must be a disposable localhost database");
            }
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.datasource.url=" + EXTERNAL_URL,
                    "spring.datasource.username=" + System.getenv().getOrDefault("SHOPLINK_TEST_DATABASE_USERNAME", "postgres"),
                    "spring.datasource.password=" + System.getenv().getOrDefault("SHOPLINK_TEST_DATABASE_PASSWORD", ""),
                    "spring.jpa.hibernate.ddl-auto=validate");
            return;
        }
        var DATABASE = Holder.DATABASE;
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "spring.datasource.url=" + DATABASE.getJdbcUrl(),
                "spring.datasource.username=" + DATABASE.getUsername(),
                "spring.datasource.password=" + DATABASE.getPassword(),
                "spring.jpa.hibernate.ddl-auto=validate");
    }
}

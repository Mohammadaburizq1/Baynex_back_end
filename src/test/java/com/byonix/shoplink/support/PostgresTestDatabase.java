package com.byonix.shoplink.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One disposable database per test JVM; no connection to developer/production databases. */
public final class PostgresTestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final class Holder {
        static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
        static { DATABASE.start(); }
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if ("false".equals(context.getEnvironment().getProperty("spring.flyway.enabled"))) return;
        var DATABASE = Holder.DATABASE;
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "spring.datasource.url=" + DATABASE.getJdbcUrl(),
                "spring.datasource.username=" + DATABASE.getUsername(),
                "spring.datasource.password=" + DATABASE.getPassword(),
                "spring.jpa.hibernate.ddl-auto=validate");
    }
}

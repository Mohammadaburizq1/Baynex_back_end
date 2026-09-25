package com.byonix.shoplink.support;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test-only shim (active only under the "test" profile, i.e. the H2 integration tests) that lets
 * V25__appointments.sql run on H2.
 *
 * V24 declares the {@code section} CHECK inline and unnamed. Postgres auto-names that
 * {@code staff_permissions_section_check}, which is the name V25 (and V27) later
 * {@code DROP CONSTRAINT}. H2 auto-names it {@code CONSTRAINT_<n>} instead, so V25 fails with
 * "Constraint STAFF_PERMISSIONS_SECTION_CHECK not found" and no @SpringBootTest context can boot.
 *
 * Renaming the constraint just before V25 runs gives H2 the same name Postgres already has,
 * without touching any already-applied migration — editing V24/V25 would change their Flyway
 * checksums and force `flyway repair` on every existing database. Nothing here runs against
 * Postgres.
 */
@Component
@Profile("test")
public class H2StaffPermissionsCheckConstraintName implements Callback {
    private static final String EXPECTED_NAME = "staff_permissions_section_check";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE
                && context.getMigrationInfo() != null
                && context.getMigrationInfo().getVersion() != null
                && "25".equals(context.getMigrationInfo().getVersion().getVersion());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try (Statement st = context.getConnection().createStatement()) {
            String actual = null;
            try (ResultSet rs = st.executeQuery("""
                    SELECT tc.CONSTRAINT_NAME
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                    JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
                      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                    WHERE tc.TABLE_NAME = 'STAFF_PERMISSIONS'
                      AND tc.CONSTRAINT_TYPE = 'CHECK'
                      AND UPPER(cc.CHECK_CLAUSE) LIKE '%SECTION%'
                    """)) {
                if (rs.next()) {
                    actual = rs.getString(1);
                }
            }
            if (actual != null && !EXPECTED_NAME.equalsIgnoreCase(actual)) {
                st.execute("ALTER TABLE staff_permissions RENAME CONSTRAINT \"" + actual + "\" TO " + EXPECTED_NAME);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not align staff_permissions CHECK constraint name for H2", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "h2-staff-permissions-check-constraint-name";
    }
}

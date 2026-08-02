package com.tengencorp.tengen.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies safety checks before Flyway can baseline or mutate a schema. */
@Configuration
public class FlywaySafetyConfiguration {

    @Bean
    FlywayMigrationStrategy safeFlywayMigrationStrategy(DataSource dataSource) {
        return flyway -> {
            try {
                TestDatabaseSafetyGuard.verify(dataSource);
                preflightExistingSchema(dataSource);
            } catch (Exception exception) {
                throw new IllegalStateException("Database safety check failed", exception);
            }
            flyway.migrate();
        };
    }

    /**
     * Baseline-on-migrate is intentionally restricted to a recognizable
     * pre-Flyway Tengen schema. A typo in DB_URL or an unrelated database must
     * fail before Flyway records a baseline and begins a partial migration.
     */
    static void preflightExistingSchema(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (hasTable(metadata, "public", "flyway_schema_history")) {
                return;
            }

            boolean hasUserTables = false;
            try (ResultSet tables = metadata.getTables(connection.getCatalog(), "public", "%",
                    new String[] {"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    if (!"flyway_schema_history".equalsIgnoreCase(table)) {
                        hasUserTables = true;
                        break;
                    }
                }
            }
            if (!hasUserTables) {
                return;
            }

            List<String> requiredTables = List.of(
                "api_keys", "events", "rules", "rule_events", "event_idempotency",
                "rule_action_state", "rule_action_windows", "webhook_outbox", "rule_revisions");
            List<String> missingTables = new ArrayList<>();
            for (String table : requiredTables) {
                if (!hasTable(metadata, "public", table)) {
                    missingTables.add(table);
                }
            }
            if (!missingTables.isEmpty()) {
                throw new IllegalStateException(
                    "Existing schema is not a recognized Tengen schema; missing tables "
                        + missingTables + ". Refusing to baseline this database.");
            }

            Map<String, List<String>> requiredColumns = Map.of(
                "events", List.of("type", "source", "occurred_at", "data"),
                "rules", List.of("name", "rule_type", "action", "event_type", "source",
                    "condition_script", "threshold", "active"),
                "webhook_outbox", List.of("event_id", "callback_url", "payload", "status"));
            List<String> missingColumns = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : requiredColumns.entrySet()) {
                for (String column : entry.getValue()) {
                    if (!hasColumn(metadata, "public", entry.getKey(), column)) {
                        missingColumns.add(entry.getKey() + "." + column);
                    }
                }
            }
            if (!missingColumns.isEmpty()) {
                throw new IllegalStateException(
                    "Existing Tengen schema is incomplete; missing columns "
                        + missingColumns + ". Refusing to baseline this database.");
            }
        }
    }

    private static boolean hasTable(DatabaseMetaData metadata, String schema, String table)
            throws SQLException {
        try (ResultSet result = metadata.getTables(null, schema, table, new String[] {"TABLE"})) {
            return result.next();
        }
    }

    private static boolean hasColumn(DatabaseMetaData metadata, String schema, String table,
                                     String column) throws SQLException {
        try (ResultSet result = metadata.getColumns(null, schema, table, column)) {
            return result.next();
        }
    }
}

package com.tengencorp.tengen.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;

/** Prevents a Spring test context from ever using the default development database. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TestDatabaseSafetyGuard implements ApplicationRunner {

    private final DataSource dataSource;

    public TestDatabaseSafetyGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        verify(dataSource);
    }

    static void verify(DataSource dataSource) throws Exception {
        if (!ClassUtils.isPresent(
                "org.junit.jupiter.api.Test", TestDatabaseSafetyGuard.class.getClassLoader())) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL().toLowerCase(Locale.ROOT);
            String database = connection.getCatalog() != null
                ? connection.getCatalog().toLowerCase(Locale.ROOT) : "";
            boolean isolated = url.startsWith("jdbc:tc:")
                || "test".equals(database)
                || database.endsWith("_test")
                || database.startsWith("test_");
            if (!isolated) {
                throw new IllegalStateException(
                    "Spring tests must use Testcontainers or an explicitly named test database; refusing "
                        + url);
            }
        }
    }
}

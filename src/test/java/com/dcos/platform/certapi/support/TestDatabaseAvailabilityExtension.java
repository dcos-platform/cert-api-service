package com.dcos.platform.certapi.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Probes the PostgreSQL test database before any Spring context is built, so an unreachable
 * database fails with a plain explanation and the command that fixes it, rather than a raw
 * connection exception buried inside a context-load failure.
 *
 * <p>Connection settings are read from the same test configuration file the application context
 * uses, with the same environment-variable overrides, so the probe and the tests cannot disagree
 * about which database they target. The probe runs once per test run and its outcome is shared.
 */
public class TestDatabaseAvailabilityExtension implements BeforeAllCallback {

    private static final String TEST_CONFIG = "config/application.yml";
    private static final String URL_PROPERTY = "spring.datasource.url";
    private static final String USER_PROPERTY = "spring.datasource.username";
    private static final String PASSWORD_PROPERTY = "spring.datasource.password";
    private static final String AVAILABLE = "";
    private static final int LOGIN_TIMEOUT_SECONDS = 5;
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestDatabaseAvailabilityExtension.class);

    /**
     * Fails the test class immediately when the test database cannot be reached.
     *
     * @param context the JUnit context of the test class about to run
     * @throws IllegalStateException naming the setup script when the database is unavailable
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        String failure =
                context.getRoot()
                        .getStore(NAMESPACE)
                        .getOrComputeIfAbsent(URL_PROPERTY, key -> probe(), String.class);
        if (!AVAILABLE.equals(failure)) {
            throw new IllegalStateException(failure);
        }
    }

    private static String probe() {
        StandardEnvironment environment = testEnvironment();
        String url = environment.getRequiredProperty(URL_PROPERTY);
        String user = environment.getRequiredProperty(USER_PROPERTY);
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        try (Connection ignored =
                DriverManager.getConnection(
                        url, user, environment.getProperty(PASSWORD_PROPERTY))) {
            return AVAILABLE;
        } catch (SQLException e) {
            return unavailableMessage(url, user, e.getMessage());
        }
    }

    private static StandardEnvironment testEnvironment() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(TEST_CONFIG));
        Properties properties = yaml.getObject();
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(new PropertiesPropertySource(TEST_CONFIG, properties));
        return environment;
    }

    private static String unavailableMessage(String url, String user, String cause) {
        return String.join(
                System.lineSeparator(),
                "",
                "TEST DATABASE UNAVAILABLE: cannot connect to " + url + " as user '" + user + "'.",
                "  Cause: " + cause,
                "  The integration tests need a running PostgreSQL with the cert_api_test"
                        + " database.",
                "  1. Start PostgreSQL, e.g. the dcos-infra stack: docker compose up -d (in"
                        + " ../dcos-infra)",
                "  2. Create the test database:",
                "       ./scripts/setup-test-db.sh      (Linux/macOS)",
                "       scripts\\setup-test-db.bat       (Windows)",
                "  Override the connection with DB_HOST, DB_PORT, DB_USER and DB_PASSWORD.");
    }
}

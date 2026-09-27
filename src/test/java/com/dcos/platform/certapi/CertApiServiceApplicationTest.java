package com.dcos.platform.certapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcos.platform.certapi.support.RequiresTestDatabase;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * Boots the full application against the migrated test database. Because Hibernate runs in {@code
 * validate} mode, a successful boot proves every entity field matches its column.
 */
@RequiresTestDatabase
@SpringBootTest
class CertApiServiceApplicationTest {

    @Autowired private Environment environment;

    @Autowired private EntityManagerFactory entityManagerFactory;

    @Autowired private Flyway flyway;

    @Test
    void contextBootsWithSchemaValidationAgainstMigratedDatabase() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
    }
}

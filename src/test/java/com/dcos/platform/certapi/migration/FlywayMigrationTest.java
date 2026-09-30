package com.dcos.platform.certapi.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcos.platform.certapi.support.RequiresTestDatabase;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Proves the Flyway migrations build the schema from nothing against real PostgreSQL. */
@RequiresTestDatabase
@SpringBootTest
class FlywayMigrationTest {

    private static final String SCHEMA = "dcos_certificates";

    private static final List<String> SEED_IDS =
            List.of(
                    "11111111-1111-4111-8111-111111111111",
                    "22222222-2222-4222-8222-222222222222",
                    "33333333-3333-4333-8333-333333333333",
                    "44444444-4444-4444-8444-444444444444",
                    "55555555-5555-4555-8555-555555555555");

    @Autowired private Flyway flyway;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private DataSource dataSource;

    @Test
    void migrationsApplyCleanlyFromAnEmptyDatabase() {
        flyway.clean();
        assertThat(schemaCount()).isZero();

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(7);
        assertThat(result.targetSchemaVersion).isEqualTo("7");
    }

    @Test
    void schemaAndCertificatesTableExist() {
        assertThat(schemaCount()).isEqualTo(1);
        Integer tables =
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = ? AND table_name = 'certificates'",
                        Integer.class,
                        SCHEMA);
        assertThat(tables).isEqualTo(1);
    }

    @Test
    void seedDataLoadsFiveFixedCertificates() {
        List<String> ids =
                jdbc.queryForList(
                        "SELECT id::text FROM dcos_certificates.certificates"
                                + " WHERE requested_by = 'seed' ORDER BY id",
                        String.class);
        assertThat(ids).containsExactlyElementsOf(SEED_IDS);
    }

    @Test
    void seedMigrationIsSafeToReRun() {
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V2__seed_demo_certificates.sql"))
                .execute(dataSource);

        Integer seeded =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dcos_certificates.certificates"
                                + " WHERE requested_by = 'seed'",
                        Integer.class);
        assertThat(seeded).isEqualTo(SEED_IDS.size());
    }

    private Integer schemaCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class,
                SCHEMA);
    }
}

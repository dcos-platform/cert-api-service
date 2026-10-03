package com.dcos.platform.certapi.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.CertificateType;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.domain.RevocationReason;
import com.dcos.platform.certapi.support.CertificateFixtures;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises retrieval against the migrated schema with test-owned fixture data on real PostgreSQL.
 */
@RequiresTestDatabase
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CertificateRepositoryTest {

    @Autowired private CertificateRepository repository;

    @Autowired private EntityManager entityManager;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID alphaId;
    private UUID bravoId;
    private UUID charlieId;
    private UUID deltaId;
    private UUID echoId;

    @BeforeEach
    void insertTestCertificates() {
        alphaId = UUID.randomUUID();
        bravoId = UUID.randomUUID();
        charlieId = UUID.randomUUID();
        deltaId = UUID.randomUUID();
        echoId = UUID.randomUUID();

        Instant now = Instant.now();
        Instant issuedAt = now.minus(90, ChronoUnit.DAYS);
        Instant expiresAt = now.plus(275, ChronoUnit.DAYS);
        String alphaSerial = "TEST-" + alphaId;
        String bravoSerial = "TEST-" + bravoId;
        String charlieSerial = "TEST-" + charlieId;
        String deltaSerial = "TEST-" + deltaId;
        String echoSerial = "TEST-" + echoId;

        insertCertificate(
                alphaId,
                alphaSerial,
                "CN=service-alpha-test,OU=test,O=DCOS",
                "service-alpha",
                "TLS",
                "ACTIVE",
                "COMPLETED",
                issuedAt,
                expiresAt);
        insertCertificate(
                bravoId,
                bravoSerial,
                "CN=service-bravo-test,OU=test,O=DCOS",
                "service-bravo",
                "TLS",
                "ACTIVE",
                "COMPLETED",
                issuedAt,
                expiresAt);
        insertCertificate(
                charlieId,
                charlieSerial,
                "CN=service-charlie-test,OU=test,O=DCOS",
                "service-charlie",
                "TLS",
                "ACTIVE",
                "COMPLETED",
                issuedAt,
                expiresAt);
        insertCertificate(
                deltaId,
                deltaSerial,
                "CN=service-delta-test,OU=test,O=DCOS",
                "service-delta",
                "TLS",
                "ACTIVE",
                "COMPLETED",
                issuedAt,
                expiresAt);
        insertCertificate(
                echoId,
                echoSerial,
                "CN=service-echo-test,OU=test,O=DCOS",
                "service-echo",
                "TLS",
                "ACTIVE",
                "COMPLETED",
                issuedAt,
                expiresAt);
    }

    private void insertCertificate(
            UUID id,
            String serialNumber,
            String subject,
            String commonName,
            String type,
            String status,
            String orchestrationStatus,
            Instant issuedAt,
            Instant expiresAt) {
        String sql =
                "INSERT INTO dcos_certificates.certificates (id, serial_number, subject, common_name, type, status, orchestration_status, issued_by, issued_at, expires_at, renewal_window_days, requested_by, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'DCOS Demo Authority', ?, ?, 30, 'seed', 0)";
        jdbcTemplate.update(
                sql,
                id,
                serialNumber,
                subject,
                commonName,
                type,
                status,
                orchestrationStatus,
                Timestamp.from(issuedAt),
                Timestamp.from(expiresAt));
    }

    @Test
    void findById_mapsEveryColumnOfATestCertificate() {
        Certificate alpha = repository.findById(alphaId).orElseThrow();

        assertThat(alpha.getSerialNumber()).isEqualTo("TEST-" + alphaId);
        assertThat(alpha.getSubject()).isEqualTo("CN=service-alpha-test,OU=test,O=DCOS");
        assertThat(alpha.getCommonName()).isEqualTo("service-alpha");
        assertThat(alpha.getType()).isEqualTo(CertificateType.TLS);
        assertThat(alpha.getStatus()).isEqualTo(CertificateStatus.ACTIVE);
        assertThat(alpha.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(alpha.getIssuedBy()).isEqualTo("DCOS Demo Authority");
        assertThat(alpha.getIssuedAt()).isBefore(Instant.now());
        assertThat(alpha.getExpiresAt()).isAfter(Instant.now());
        assertThat(alpha.getRenewalWindowDays()).isEqualTo(30);
        assertThat(alpha.getRevokedAt()).isNull();
        assertThat(alpha.getRevocationReason()).isNull();
        assertThat(alpha.getRequestedBy()).isEqualTo("seed");
        assertThat(alpha.getRenewalCount()).isZero();
        assertThat(alpha.getCreatedAt()).isNotNull();
        assertThat(alpha.getUpdatedAt()).isNotNull();
        assertThat(alpha.getVersion()).isZero();
    }

    @Test
    void findAll_returnsTheTestCertificates() {
        var allCerts = repository.findAll();

        assertThat(allCerts)
                .extracting(Certificate::getId)
                .contains(alphaId, bravoId, charlieId, deltaId, echoId);
    }

    @Test
    void findByStatus_returnsOnlyCertificatesInThatStatus() {
        Certificate revoked = repository.saveAndFlush(CertificateFixtures.revoked());

        List<Certificate> active = repository.findByStatus(CertificateStatus.ACTIVE);
        List<Certificate> revokedOnly = repository.findByStatus(CertificateStatus.REVOKED);

        assertThat(active)
                .extracting(Certificate::getId)
                .contains(alphaId, bravoId, charlieId, deltaId, echoId);
        assertThat(active)
                .extracting(Certificate::getStatus)
                .containsOnly(CertificateStatus.ACTIVE);
        assertThat(revokedOnly).extracting(Certificate::getId).contains(revoked.getId());
        assertThat(revokedOnly)
                .extracting(Certificate::getStatus)
                .containsOnly(CertificateStatus.REVOKED);
    }

    @Test
    void findBySubjectContainingIgnoreCase_matchesRegardlessOfCase() {
        List<Certificate> found = repository.findBySubjectContainingIgnoreCase("SERVICE-ECHO-TEST");

        assertThat(found).extracting(Certificate::getId).containsExactly(echoId);
    }

    @Test
    void fixtureCertificatesRoundTripThroughTheSchema() {
        List<Certificate> saved =
                repository.saveAllAndFlush(
                        List.of(
                                CertificateFixtures.active(),
                                CertificateFixtures.expired(),
                                CertificateFixtures.revoked(),
                                CertificateFixtures.inRenewalWindow()));
        entityManager.clear();

        for (Certificate original : saved) {
            Certificate reloaded = repository.findById(original.getId()).orElseThrow();
            assertThat(reloaded.getSerialNumber()).isEqualTo(original.getSerialNumber());
            assertThat(reloaded.getStatus()).isEqualTo(original.getStatus());
            assertThat(reloaded.getOrchestrationStatus()).isEqualTo(OrchestrationStatus.PENDING);
            assertThat(reloaded.getExpiresAt())
                    .isCloseTo(original.getExpiresAt(), within(1, ChronoUnit.MILLIS));
            assertThat(reloaded.getCreatedAt()).isNotNull();
            assertThat(reloaded.getVersion()).isZero();
        }
    }

    @Test
    void revokedFixture_persistsRevocationDetail() {
        Certificate saved = repository.saveAndFlush(CertificateFixtures.revoked());
        entityManager.clear();

        Certificate reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(CertificateStatus.REVOKED);
        assertThat(reloaded.getRevokedAt()).isNotNull();
        assertThat(reloaded.getRevocationReason()).isEqualTo(RevocationReason.KEY_COMPROMISE);
        assertThat(reloaded.getRevocationComment()).isEqualTo("revoked by fixture");
    }

    @Test
    void findAll_withSpecification_returnsOnlyMatchingRows() {
        Certificate testRevoked = repository.saveAndFlush(CertificateFixtures.revoked());
        Specification<Certificate> revokedSpec =
                (root, query, cb) -> cb.equal(root.get("status"), CertificateStatus.REVOKED);

        Page<Certificate> result = repository.findAll(revokedSpec, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(Certificate::getId)
                .contains(testRevoked.getId());
        assertThat(result.getContent())
                .extracting(Certificate::getStatus)
                .containsOnly(CertificateStatus.REVOKED);
    }

    @Test
    void findAll_withPaging_returnEmptyPageWhenBeyondRange() {
        Page<Certificate> firstPage = repository.findAll(PageRequest.of(0, 10));
        long totalPages = firstPage.getTotalPages();

        Page<Certificate> beyondRange =
                repository.findAll(PageRequest.of((int) totalPages + 1, 10));

        assertThat(beyondRange.getContent()).isEmpty();
        assertThat(beyondRange.getTotalElements()).isEqualTo(firstPage.getTotalElements());
        assertThat(beyondRange.isLast()).isTrue();
    }
}

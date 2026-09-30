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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

/** Exercises retrieval against the migrated schema and its seed data on real PostgreSQL. */
@RequiresTestDatabase
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CertificateRepositoryTest {

    private static final UUID ALPHA_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ECHO_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Autowired private CertificateRepository repository;

    @Autowired private EntityManager entityManager;

    @Test
    void findById_mapsEveryColumnOfASeededCertificate() {
        Certificate alpha = repository.findById(ALPHA_ID).orElseThrow();

        assertThat(alpha.getSerialNumber()).isEqualTo("DCOS-2026-A1B2C3D4E5F6");
        assertThat(alpha.getSubject()).isEqualTo("CN=service-alpha,OU=platform,O=DCOS");
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
    void findAll_returnsTheFiveSeededCertificates() {
        List<UUID> ids = repository.findAll().stream().map(Certificate::getId).toList();

        assertThat(ids)
                .contains(
                        ALPHA_ID,
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        UUID.fromString("33333333-3333-4333-8333-333333333333"),
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        ECHO_ID);
    }

    @Test
    void findByStatus_returnsOnlyCertificatesInThatStatus() {
        Certificate revoked = repository.saveAndFlush(CertificateFixtures.revoked());

        List<Certificate> active = repository.findByStatus(CertificateStatus.ACTIVE);
        List<Certificate> revokedOnly = repository.findByStatus(CertificateStatus.REVOKED);

        assertThat(active).extracting(Certificate::getId).contains(ALPHA_ID, ECHO_ID);
        assertThat(active)
                .extracting(Certificate::getStatus)
                .containsOnly(CertificateStatus.ACTIVE);
        assertThat(revokedOnly).extracting(Certificate::getId).containsExactly(revoked.getId());
    }

    @Test
    void findBySubjectContainingIgnoreCase_matchesRegardlessOfCase() {
        List<Certificate> found = repository.findBySubjectContainingIgnoreCase("SERVICE-ECHO");

        assertThat(found).extracting(Certificate::getId).containsExactly(ECHO_ID);
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
    void findAll_withSpecification_returnsEmptyWhenNoMatch() {
        Specification<Certificate> noMatch =
                (root, query, cb) -> cb.equal(root.get("status"), CertificateStatus.REVOKED);

        Page<Certificate> result = repository.findAll(noMatch, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
        assertThat(result.isFirst()).isTrue();
        assertThat(result.isLast()).isTrue();
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

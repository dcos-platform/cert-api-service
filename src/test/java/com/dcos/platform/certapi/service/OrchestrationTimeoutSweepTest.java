package com.dcos.platform.certapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import com.dcos.platform.certapi.repository.CertificateRepository;
import com.dcos.platform.certapi.support.CertificateFixtures;
import com.dcos.platform.certapi.support.RequiresTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@RequiresTestDatabase
@DisplayName("OrchestrationTimeoutSweep tests (real DB)")
class OrchestrationTimeoutSweepTest {

    @Autowired private OrchestrationTimeoutSweep sweep;
    @Autowired private CertificateRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    @Transactional
    @DisplayName("sweep: moves stale pending certificates to FAILED")
    void moveStalePendingToFailed() {
        // Create a certificate that has been pending for more than 2 minutes
        Certificate staleCert = CertificateFixtures.active();
        staleCert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        repository.save(staleCert);

        // Manually set updatedAt to bypass Hibernate's timestamp update
        entityManager
                .createNativeQuery(
                        "UPDATE dcos_certificates.certificates SET updated_at = ? WHERE id = ?")
                .setParameter(1, Instant.now().minus(3, ChronoUnit.MINUTES))
                .setParameter(2, staleCert.getId())
                .executeUpdate();

        // Create a recent pending certificate that should NOT be swept
        Certificate recentCert = CertificateFixtures.active();
        recentCert.setOrchestrationStatus(OrchestrationStatus.PENDING);
        repository.save(recentCert);

        // T2: Record counter before sweep (per-tag)
        double countBefore =
                meterRegistry
                        .find("cert.sweep.transitions")
                        .tag("sweep", "orchestration.timeout")
                        .counters()
                        .stream()
                        .mapToDouble(c -> c.count())
                        .sum();

        // Run sweep
        sweep.sweep();

        // Verify stale certificate was moved to FAILED
        Optional<Certificate> updated = repository.findById(staleCert.getId());
        assertThat(updated)
                .isPresent()
                .get()
                .satisfies(
                        c -> {
                            assertThat(c.getOrchestrationStatus())
                                    .isEqualTo(OrchestrationStatus.FAILED);
                            assertThat(c.getLastError()).isEqualTo("orchestration timed out");
                        });

        // Verify recent certificate was NOT swept
        Optional<Certificate> recentUpdated = repository.findById(recentCert.getId());
        assertThat(recentUpdated)
                .isPresent()
                .get()
                .satisfies(
                        c -> {
                            assertThat(c.getOrchestrationStatus())
                                    .isEqualTo(OrchestrationStatus.PENDING);
                            assertThat(c.getLastError()).isNull();
                        });

        // T2: Verify counter incremented by 1 (delta-based, per-tag)
        double countAfter =
                meterRegistry
                        .find("cert.sweep.transitions")
                        .tag("sweep", "orchestration.timeout")
                        .counters()
                        .stream()
                        .mapToDouble(c -> c.count())
                        .sum();
        assertThat(countAfter - countBefore).isEqualTo(1.0);
    }

    @Test
    @Transactional
    @DisplayName("sweep: ignores already-completed certificates")
    void ignoreCompletedCertificates() {
        // Create an old completed certificate
        Certificate completedCert = CertificateFixtures.active();
        completedCert.setOrchestrationStatus(OrchestrationStatus.COMPLETED);
        repository.save(completedCert);

        // Manually set updatedAt to bypass Hibernate's timestamp update
        entityManager
                .createNativeQuery(
                        "UPDATE dcos_certificates.certificates SET updated_at = ? WHERE id = ?")
                .setParameter(1, Instant.now().minus(10, ChronoUnit.MINUTES))
                .setParameter(2, completedCert.getId())
                .executeUpdate();

        // Create an old failed certificate
        Certificate failedCert = CertificateFixtures.active();
        failedCert.setOrchestrationStatus(OrchestrationStatus.FAILED);
        failedCert.setLastError("some error");
        repository.save(failedCert);

        // Manually set updatedAt to bypass Hibernate's timestamp update
        entityManager
                .createNativeQuery(
                        "UPDATE dcos_certificates.certificates SET updated_at = ? WHERE id = ?")
                .setParameter(1, Instant.now().minus(10, ChronoUnit.MINUTES))
                .setParameter(2, failedCert.getId())
                .executeUpdate();

        // Run sweep
        sweep.sweep();

        // Verify completed certificate was not modified
        Optional<Certificate> completedUpdated = repository.findById(completedCert.getId());
        assertThat(completedUpdated)
                .isPresent()
                .get()
                .satisfies(
                        c -> {
                            assertThat(c.getOrchestrationStatus())
                                    .isEqualTo(OrchestrationStatus.COMPLETED);
                            assertThat(c.getLastError()).isNull();
                        });

        // Verify failed certificate was not modified
        Optional<Certificate> failedUpdated = repository.findById(failedCert.getId());
        assertThat(failedUpdated)
                .isPresent()
                .get()
                .satisfies(
                        c -> {
                            assertThat(c.getOrchestrationStatus())
                                    .isEqualTo(OrchestrationStatus.FAILED);
                            assertThat(c.getLastError()).isEqualTo("some error");
                        });
    }
}

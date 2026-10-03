package com.dcos.platform.certapi.repository;

import com.dcos.platform.certapi.domain.Certificate;
import com.dcos.platform.certapi.domain.CertificateStatus;
import com.dcos.platform.certapi.domain.OrchestrationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Data access for Certificate entities. Extends JpaRepository for CRUD and finder method
 * generation, and JpaSpecificationExecutor for flexible filtering via Specifications.
 */
@Repository
public interface CertificateRepository
        extends JpaRepository<Certificate, UUID>, JpaSpecificationExecutor<Certificate> {

    List<Certificate> findByStatus(CertificateStatus status);

    List<Certificate> findBySubjectContainingIgnoreCase(String subject);

    /**
     * Checks whether a certificate with the given serial number exists.
     *
     * @param serialNumber the serial number to look up
     * @return true if a certificate with this serial number exists
     */
    boolean existsBySerialNumber(String serialNumber);

    /**
     * Finds active certificates whose expiry has passed.
     *
     * @param status the certificate status (ACTIVE)
     * @param now the current time
     * @param pageable pagination parameters
     * @return list of expired certificates
     */
    List<Certificate> findByStatusAndExpiresAtBefore(
            CertificateStatus status, Instant now, Pageable pageable);

    /**
     * Finds certificates stuck in orchestration pending state before a given cutoff time.
     *
     * @param orchestrationStatus the orchestration status (PENDING)
     * @param cutoff the time threshold
     * @param pageable pagination parameters
     * @return list of stale pending certificates
     */
    List<Certificate> findByOrchestrationStatusAndUpdatedAtBefore(
            OrchestrationStatus orchestrationStatus, Instant cutoff, Pageable pageable);

    /**
     * Counts certificates grouped by status and type for metrics collection.
     *
     * @return list of status-type combinations with their counts
     */
    @Query(
            "select c.status as status, c.type as type, count(c) as count "
                    + "from Certificate c group by c.status, c.type")
    List<StatusTypeCount> countGroupedByStatusAndType();

    /**
     * Counts certificates grouped by orchestration status for metrics collection.
     *
     * @return list of orchestration-status combinations with their counts
     */
    @Query(
            "select c.orchestrationStatus as orchestrationStatus, count(c) as count "
                    + "from Certificate c group by c.orchestrationStatus")
    List<OrchestrationStatusCount> countGroupedByOrchestrationStatus();

    /**
     * Counts certificates that are ACTIVE, within their renewal window, and not yet expired. A
     * certificate is in its renewal window if it is ACTIVE, expires after now, and expires within
     * (now + renewal_window_days).
     *
     * @return count of certificates in renewal window
     */
    @Query(
            value =
                    "SELECT COUNT(*) FROM dcos_certificates.certificates c "
                            + "WHERE c.status = 'ACTIVE' "
                            + "AND c.expires_at > NOW() AT TIME ZONE 'UTC' "
                            + "AND c.expires_at <= (NOW() AT TIME ZONE 'UTC') + (c.renewal_window_days * INTERVAL '1 day')",
            nativeQuery = true)
    long countCertificatesInRenewalWindow();
}
